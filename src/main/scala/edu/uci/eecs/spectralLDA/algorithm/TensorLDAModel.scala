package edu.uci.eecs.spectralLDA.algorithm

import breeze.linalg.{*, DenseMatrix, DenseVector, SparseVector, Vector, diag, norm, sum}
import breeze.numerics.{abs, lgamma, log, sqrt}
import breeze.stats.distributions.{Dirichlet, Rand, RandBasis}
import org.apache.spark.rdd.RDD


class TensorLDAModel(val topicWordDistribution: DenseMatrix[Double],
                     val alpha: DenseVector[Double],
                     val eigenVectorsM2: DenseMatrix[Double],
                     val eigenValuesM2: DenseVector[Double])
                    (implicit smoothing: Double = 0.01)
    extends Serializable {

  assert(topicWordDistribution.cols == alpha.length)
  assert(topicWordDistribution.forall(_ > - 1e-12))
  assert(alpha.forall(_ > 1e-10))

  private val k = alpha.length
  private val vocabSize = topicWordDistribution.rows

  private val whiteningMatrix: DenseMatrix[Double] = eigenVectorsM2 * diag(1.0 / sqrt(eigenValuesM2))

  // smoothing so that beta is positive
  val smoothedBeta: DenseMatrix[Double] = topicWordDistribution * (1 - smoothing)
  smoothedBeta += DenseMatrix.ones[Double](vocabSize, k) * (smoothing / vocabSize)

  assert(sum(smoothedBeta(::, *)).toDenseVector.forall(a => abs(a - 1) <= 1e-10))
  assert(smoothedBeta.forall(_ > 1e-10))

  private val whitenedBeta = whiteningMatrix.t * smoothedBeta

  /** compute sum of loglikelihood(doc|topics over the doc, alpha, beta) */
  def logLikelihood(docs: RDD[(Long, SparseVector[Double])],
                    maxIterationsEM: Int = 3)
      : Double = {
    docs
      .map {
        case (id: Long, wordCounts: SparseVector[Double]) =>
          val topicDistribution: DenseVector[Double] = inferTopicDistribution(whitenedBeta,
              whiteningMatrix.t * wordCounts, maxIterationsEM)
          TensorLDAModel.multinomialLogLikelihood(smoothedBeta * topicDistribution, wordCounts)
      }
      .sum
  }

  def inferTopicDistribution(beta: DenseMatrix[Double],
                             wordCounts: Vector[Double],
                             maxIterationsEM: Int)
                            (implicit randBasis: RandBasis = Rand)
  : DenseVector[Double] = {
    var prior = alpha.copy
    for (i <- 0 until maxIterationsEM) {
      val updatedPrior = stepEM(prior, beta, wordCounts)
      prior = updatedPrior
    }

    new Dirichlet(prior).sample()
  }

  /** Update the topic distribution prior by EM */
  private def stepEM(topicDistributionPrior: DenseVector[Double],
                     beta: DenseMatrix[Double],
                     wordCounts: Vector[Double])
                    (implicit randBasis: RandBasis = Rand)
      : DenseVector[Double] = {
    val topicDistributionSample: DenseVector[Double] =
      new Dirichlet(topicDistributionPrior).sample()

    val expectedWordCounts: DenseVector[Double] = beta * topicDistributionSample
    val priorIncrement: Seq[Double] = for {
      j <- 0 until k
      latentTopicAttribution = beta(::, j) * topicDistributionSample(j) / expectedWordCounts
      wordCountsCurrentTopic = wordCounts :* latentTopicAttribution
    } yield sum(wordCountsCurrentTopic)

    val updatedPrior = topicDistributionPrior + DenseVector[Double](priorIncrement: _*)
    assert(updatedPrior.forall(_ > 0.0))

    updatedPrior
  }
}

private[algorithm] object TensorLDAModel {
  /** compute the loglikelihood of sample x for multinomial(p) */
  def multinomialLogLikelihood(p: DenseVector[Double],
                               x: Vector[Double])
      : Double = {
    assert(p.length == x.length)
    assert(p forall(_ > - 1e-12))
    assert(x forall(_ > - 1e-12))

    val coeff: Double = lgamma(sum(x) + 1) - sum(x.map(a => lgamma(a + 1)))
    coeff + sum(x :* log(p))
  }
}