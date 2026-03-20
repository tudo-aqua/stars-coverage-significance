/*
 * Copyright 2026 The STARS Coverage Significance Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tools.aqua.stars.coverage.significance.postEvaluation

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ScenarioIdAndJSON

object HighwayTrafficAnalysis {
  fun evaluate(longtailDistribution: List<Pair<ScenarioIdAndJSON, Int>>) {
    // x = rank (1..n), y = count
    val positivePoints =
        longtailDistribution
            .filter { it.second > 0.0 }
            .mapIndexed { idx, (_, count) -> (idx + 1).toDouble() to count.toDouble() }

    val (a, b, r2LogExp) = fitExponential(positivePoints)
    val (c, k, r2LogPow) = fitPowerLaw(positivePoints)
    val betterByLogR2 = if (r2LogExp >= r2LogPow) "exponential" else "power-law"
    println("Better (by log-space R^2): $betterByLogR2")
  }

  private fun fitPowerLaw(
      positivePoints: List<Pair<Double, Double>>
  ): Triple<Double, Double, Double> {
    val xs = positivePoints.map { it.first }
    val ys = positivePoints.map { it.second }
    val logYs = ys.map(::ln)

    // Power law: y = c * x^k  ->  ln(y) = ln(c) + k*ln(x)
    val logXs = xs.map(::ln)
    val powLin = fitLinear(logXs, logYs)
    val c = exp(powLin.intercept)
    val k = powLin.slope
    val yPredPow = xs.map { x -> c * x.pow(k) }
    val logPredPow = yPredPow.map(::ln)

    val r2LinearPow = rSquared(ys, yPredPow)
    val r2LogPow = rSquared(logYs, logPredPow)
    val rmseLogPow = rmse(logYs, logPredPow)

    println("---- Power-Law curve fitting on long-tail distribution ----")
    println("Fitted points: ${positivePoints.size}")
    println("Power-law fit: y = $c * x^$k")
    println("  R^2 linear = $r2LinearPow")
    println("  R^2 log    = $r2LogPow")
    println("  RMSE log   = $rmseLogPow")

    return Triple(c, k, r2LogPow)
  }

  private fun fitExponential(
      positivePoints: List<Pair<Double, Double>>
  ): Triple<Double, Double, Double> {
    val xs = positivePoints.map { it.first }
    val ys = positivePoints.map { it.second }
    val logYs = ys.map(::ln)

    val expLin = fitLinear(xs, logYs)
    val a = exp(expLin.intercept)
    val b = expLin.slope
    val yPredExp = xs.map { x -> a * exp(b * x) }
    val logPredExp = yPredExp.map(::ln)

    // Metriken: sowohl linear als auch log
    val r2LinearExp = rSquared(ys, yPredExp)
    val r2LogExp = rSquared(logYs, logPredExp)
    val rmseLogExp = rmse(logYs, logPredExp)

    println("---- Exponential curve fitting on long-tail distribution ----")
    println("Fitted points: ${positivePoints.size}")
    println("Exponential fit: y = $a * exp($b * x)")
    println("  R^2 linear = $r2LinearExp")
    println("  R^2 log    = $r2LogExp")
    println("  RMSE log   = $rmseLogExp")

    return Triple(a, b, r2LogExp)
  }

  // Ordinary least squares: y = intercept + slope * x
  private fun fitLinear(xs: List<Double>, ys: List<Double>): LinearFit {
    require(xs.size == ys.size && xs.size >= 2) { "Invalid input sizes for linear fit." }

    val n = xs.size.toDouble()
    val meanX = xs.sum() / n
    val meanY = ys.sum() / n

    val sxx = xs.sumOf { (it - meanX) * (it - meanX) }
    require(sxx > 0.0) { "Cannot fit linear model: zero variance in x." }

    val sxy = xs.indices.sumOf { i -> (xs[i] - meanX) * (ys[i] - meanY) }

    val slope = sxy / sxx
    val intercept = meanY - slope * meanX
    return LinearFit(intercept, slope)
  }

  private fun rSquared(yTrue: List<Double>, yPred: List<Double>): Double {
    require(yTrue.size == yPred.size && yTrue.isNotEmpty())
    val mean = yTrue.average()
    val ssTot = yTrue.sumOf { (it - mean) * (it - mean) }
    if (ssTot == 0.0) return 1.0
    val ssRes = yTrue.indices.sumOf { i -> (yTrue[i] - yPred[i]) * (yTrue[i] - yPred[i]) }
    return 1.0 - (ssRes / ssTot)
  }

  private fun rmse(yTrue: List<Double>, yPred: List<Double>): Double {
    require(yTrue.size == yPred.size && yTrue.isNotEmpty())
    val mse =
        yTrue.indices.sumOf { i ->
          val d = yTrue[i] - yPred[i]
          d * d
        } / yTrue.size
    return sqrt(mse)
  }

  private data class LinearFit(val intercept: Double, val slope: Double)
}
