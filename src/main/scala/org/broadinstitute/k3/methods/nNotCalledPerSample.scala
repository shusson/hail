package org.broadinstitute.k3.methods

import org.broadinstitute.k3.variant._

// FIXME need to account for all HomRef?
object nNotCalledPerSample extends SampleMethod[Int] {
  def name = "nNotCalled"

  def apply(vds: VariantDataset): Map[Int, Int] = {
    vds
      .mapValues(g => if (g.isNotCalled) 1 else 0)
      .foldBySample(0)(_ + _)
  }
}