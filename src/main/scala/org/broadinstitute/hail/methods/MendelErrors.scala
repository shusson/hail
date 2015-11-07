package org.broadinstitute.hail.methods

import org.apache.spark.rdd.RDD
import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.variant._

import org.broadinstitute.hail.variant.GenotypeType._

case class MendelError(variant: Variant, sample: Int, code: Int,
                       gtKid: GenotypeType, gtDad: GenotypeType, gtMom: GenotypeType)

object MendelErrors {

  def variantString(v: Variant): String = v.contig + ":" + v.start + ":" + v.ref + ":" + v.alt

  def getCode(gts: Array[GenotypeType], isHemizygous: Boolean): Int = {
    (gts(0), gts(1), gts(2), isHemizygous) match {
      case (HomRef, HomRef,    Het, false) => 2  // Kid is het and not hemizygous
      case (HomVar, HomVar,    Het, false) => 1
      case (HomRef, HomRef, HomVar, false) => 5  // Kid is homvar and not hemizygous
      case (HomRef,      _, HomVar, false) => 3
      case (     _, HomRef, HomVar, false) => 4
      case (HomVar, HomVar, HomRef, false) => 8  // Kid is homref and not hemizygous
      case (HomVar,      _, HomRef, false) => 6
      case (     _, HomVar, HomRef, false) => 7
      case (     _, HomVar, HomRef,  true) => 9  // Kid is homref and hemizygous
      case (     _, HomRef, HomVar,  true) => 10 // Kid is homvar and hemizygous
      case _                               => 0  // No error
    }
  }

  def apply(vds: VariantDataset, ped: Pedigree): MendelErrors = {
    require(ped.sexDefinedForAll)

    val nCompleteTrios = ped.completeTrios.size
    val completeTrioIndex: Map[Trio, Int] = ped.completeTrios.zipWithIndex.toMap

    val sampleIndex: Map[Int, Int] = ped.samplesInCompleteTrios.zipWithIndex.toMap
    val nSamplesInCompleteTrios = sampleIndex.size

    val sampleIndexTrioRoles: Array[List[(Int, Int)]] = {
      val a: Array[List[(Int, Int)]] = Array.fill[List[(Int, Int)]](nSamplesInCompleteTrios)(List())
      ped.completeTrios.flatMap { t => {
          val ti = completeTrioIndex(t)
          List((sampleIndex(t.kid), (ti, 0)), (sampleIndex(t.dad.get), (ti, 1)), (sampleIndex(t.mom.get), (ti, 2)))
        }
      }
      .foreach{ case (si, tiri) => a(si) ::= tiri }
      a
    }

    val zeroVal: Array[Array[GenotypeType]] =
      Array.fill[Array[GenotypeType]](nSamplesInCompleteTrios)(Array.fill[GenotypeType](3)(NoCall))

    def seqOp(a: Array[Array[GenotypeType]], s: Int, g: Genotype): Array[Array[GenotypeType]] = {
      sampleIndexTrioRoles(sampleIndex(s)).foreach { case (ti, ri) => a(ti)(ri) = g.gtType }
      a
    }

    def mergeOp(a: Array[Array[GenotypeType]], b: Array[Array[GenotypeType]]): Array[Array[GenotypeType]] = {
      for (si <- a.indices)
        for (ri <- 0 to 2)
          if (b(si)(ri) != NoCall)
            a(si)(ri) = b(si)(ri)
      a
    }


    val sexOfBc = vds.sparkContext.broadcast(ped.sexOf)

    new MendelErrors(ped, vds.sampleIds,
      vds
      .aggregateByVariantWithKeys(zeroVal)(
        (a, v, s, g) => seqOp(a, s, g),
        mergeOp)
      .flatMap{ case (v, a) =>
        a.indices.flatMap{
          si => {
            val code = getCode(a(si), v.isHemizygous(sexOfBc.value(si)))
            if (code != 0)
              Some(new MendelError(v, ped.samplesInCompleteTrios(si), code, a(si)(0), a(si)(1), a(si)(2)))
            else
              None
          }
        }
      }
      .cache()
    )
  }
}

case class MendelErrors(ped:          Pedigree,
                        sampleIds:    Array[String],
                        mendelErrors: RDD[MendelError]) {
  require(ped.sexDefinedForAll)

  def sc = mendelErrors.sparkContext

  val dadOf = sc.broadcast(ped.dadOf)
  val momOf = sc.broadcast(ped.momOf)
  val famOf = sc.broadcast(ped.famOf)
  val sampleIdsBc = sc.broadcast(sampleIds)

  def nErrorPerVariant: RDD[(Variant, Int)] = {
    mendelErrors
      .map(_.variant)
      .countByValueRDD()
  }

  def nErrorPerNuclearFamily: RDD[((Int, Int), Int)] = {
    val parentsRDD = sc.parallelize(ped.nuclearFams.keys.toSeq)
    mendelErrors
      .map(me => ((dadOf.value(me.sample), momOf.value(me.sample)), 1))
      .union(parentsRDD.map((_, 0)))
      .reduceByKey(_ + _)
  }

  def nErrorPerIndiv: RDD[(Int, Int)] = {
    val indivRDD = sc.parallelize(ped.trioMap.keys.toSeq)
    def implicatedSamples(me: MendelError): List[Int] = {
      val s = me.sample
      val c = me.code
      if      (c == 2 || c == 1)                       List(s, dadOf.value(s), momOf.value(s))
      else if (c == 6 || c == 3)                       List(s, dadOf.value(s))
      else if (c == 4 || c == 7 || c == 9 || c == 10)  List(s, momOf.value(s))
      else                                             List(s)
    }
    mendelErrors
      .flatMap(implicatedSamples)
      .map((_, 1))
      .union(indivRDD.map((_, 0)))
      .reduceByKey(_ + _)
  }

  def writeMendel(filename: String) {
    def gtString(v: Variant, gt: GenotypeType): String = {
      if (gt == HomRef)
        v.ref + "/" + v.ref
      else if (gt == Het)
        v.ref + "/" + v.alt
      else if (gt == HomVar)
        v.alt + "/" + v.alt
      else
        "./."
    }
    def toLine(me: MendelError): String = {
      val v = me.variant
      val s = me.sample
      val errorString = gtString(v, me.gtDad) + " x " + gtString(v, me.gtMom) + " -> " + gtString(v, me.gtKid)
      famOf.value.getOrElse(s, "0") + "\t" + sampleIdsBc.value(s) + "\t" + v.contig + "\t" +
        MendelErrors.variantString(v) + "\t" + me.code + "\t" + errorString
    }
    mendelErrors.map(toLine)
      .writeTable(filename, "FID\tKID\tCHR\tSNP\tCODE\tERROR\n")
  }

  def writeMendelL(filename: String) {
    def toLine(v: Variant, nError: Int) = v.contig + "\t" + MendelErrors.variantString(v) + "\t" + nError
    nErrorPerVariant.map((toLine _).tupled)
      .writeTable(filename, "CHR\tSNP\tN\n")
  }

  def writeMendelF(filename: String) {
    val nuclearFams = sc.broadcast(ped.nuclearFams.force)
    def toLine(parents: (Int, Int), nError: Int): String = {
      val (dad, mom) = parents
      famOf.value.getOrElse(dad, "0") + "\t" + sampleIdsBc.value(dad) + "\t" + sampleIdsBc.value(mom) + "\t" +
        nuclearFams.value((dad, mom)).size + "\t" + nError + "\n"
    }
    val lines = nErrorPerNuclearFamily.map((toLine _).tupled).collect()
    writeTable(filename, sc.hadoopConfiguration, lines, "FID\tPAT\tMAT\tCHLD\tN\n")
  }

  def writeMendelI(filename: String) {
    def toLine(s: Int, nError: Int): String =
      famOf.value.getOrElse(s, "0") + "\t" + sampleIdsBc.value(s) + "\t" + nError + "\n"
    val lines = nErrorPerIndiv.map((toLine _).tupled).collect()
    writeTable(filename, sc.hadoopConfiguration, lines, "FID\tIID\tN\n")
  }
}
