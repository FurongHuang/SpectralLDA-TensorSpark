package edu.uci.eecs.spectralLDA.algorithm

/**
 * Tensor Decomposition Algorithms.
 * Alternating Least Square algorithm is implemented.
 * Created by Furong Huang on 11/2/15.
 */
import edu.uci.eecs.spectralLDA.datamoments.DataCumulant
import breeze.linalg.{DenseMatrix, DenseVector, SparseVector, argtopk, diag, max, min}
import breeze.numerics._
import breeze.stats.distributions.{Rand, RandBasis}
import edu.uci.eecs.spectralLDA.utils.NonNegativeAdjustment
import org.apache.spark.rdd.RDD

class TensorLDA(dimK: Int,
                alpha0: Double,
                maxIterations: Int = 200,
                idfLowerBound: Double = 1.0,
                m2ConditionNumberUB: Double = Double.PositiveInfinity,
                randomisedSVD: Boolean = true)
               (implicit tolerance: Double = 1e-9)
                extends Serializable {
  assert(dimK > 0, "The number of topics dimK must be positive.")
  assert(alpha0 > 0, "The topic concentration alpha0 must be positive.")
  assert(maxIterations > 0, "The number of iterations for ALS must be positive.")

  def fit(documents: RDD[(Long, SparseVector[Double])])
         (implicit randBasis: RandBasis = Rand)
          : (DenseMatrix[Double], DenseVector[Double], DenseMatrix[Double], DenseVector[Double]) = {
    val cumulant: DataCumulant = DataCumulant.getDataCumulant(
      dimK,
      alpha0,
      documents,
      idfLowerBound,
      m2ConditionNumberUB,
      randomisedSVD = randomisedSVD
    )

    val myALS: ALS = new ALS(
      dimK,
      cumulant.thirdOrderMoments,
      maxIterations = maxIterations
    )

    val (nu: DenseMatrix[Double], lambda: DenseVector[Double]) = myALS.run

    // unwhiten the results
    // unwhitening matrix: $(W^T)^{-1}=U\Sigma^{1/2}$
    val unwhiteningMatrix = cumulant.eigenVectorsM2 * diag(sqrt(cumulant.eigenValuesM2))

    val alphaUnordered: DenseVector[Double] = lambda.map(x => scala.math.pow(x, -2))
    val topicWordMatrixUnordered: DenseMatrix[Double] = unwhiteningMatrix * nu * diag(lambda)

    // re-arrange alpha and topicWordMatrix in descending order of alpha
    val idx = argtopk(alphaUnordered, dimK)
    val alpha = alphaUnordered(idx).toDenseVector
    val topicWordMatrix = topicWordMatrixUnordered(::, idx).toDenseMatrix

    // Diagnostic information: the ratio of the maximum to the minimum of the
    // top k eigenvalues of shifted M2
    //
    // If it's too large (>10), the algorithm may not be able to output reasonable results.
    // It could be due to some very frequent (low IDF) words or that we specified dimK
    // larger than the rank of the shifted M2.
    val m2ConditionNumber = max(cumulant.eigenValuesM2) / min(cumulant.eigenValuesM2)
    println(s"Shifted M2 top $dimK eigenvalues: ${cumulant.eigenValuesM2}")
    println(s"Shifted M2 condition number: $m2ConditionNumber")
    println("If the condition number is too large (e.g. >10), the algorithm may not be able to " +
      "output reasonable results. It could be due to the existence of very frequent words " +
      "across the documents or that the specified k is larger than the true number of topics.")

    (NonNegativeAdjustment.simplexProj_Matrix(topicWordMatrix), alpha,
      cumulant.eigenVectorsM2, cumulant.eigenValuesM2)
  }

}
