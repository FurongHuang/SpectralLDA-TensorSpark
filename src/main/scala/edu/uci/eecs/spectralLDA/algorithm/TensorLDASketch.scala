package edu.uci.eecs.spectralLDA.algorithm

/**
  * Tensor Decomposition Algorithms.
  * Alternating Least Square algorithm is implemented.
  */
import edu.uci.eecs.spectralLDA.datamoments.{DataCumulant, DataCumulantSketch}
import breeze.linalg.{DenseMatrix, DenseVector, SparseVector, sum}
import edu.uci.eecs.spectralLDA.sketch.TensorSketcher
import org.apache.spark.rdd.RDD

class TensorLDASketch(dimK: Int,
                      alpha0: Double,
                      maxIterations: Int = 1000,
                      tolerance: Double = 1e-9,
                      sketcher: TensorSketcher[Double, Double]) extends Serializable {

  def fit(documents: RDD[(Long, SparseVector[Double])])
  : (DenseMatrix[Double], DenseVector[Double]) = {
    val documents_ = documents map {
      case (id, wc) => (id, sum(wc), wc)
    }

    val myDataSketch: DataCumulantSketch = DataCumulantSketch.getDataCumulant(
      dimK, alpha0,
      tolerance,
      documents_,
      sketcher
    )

    val myALS: ALSSketch = new ALSSketch(dimK, myDataSketch, sketcher)
    myALS.run(documents.sparkContext, maxIterations)
  }

}