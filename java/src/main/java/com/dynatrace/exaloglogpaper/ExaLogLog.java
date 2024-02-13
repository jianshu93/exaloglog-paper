//
// Copyright (c) 2024 Dynatrace LLC. All rights reserved.
//
// This software and associated documentation files (the "Software")
// are being made available by Dynatrace LLC for the sole purpose of
// illustrating the implementation of certain algorithms which have
// been published by Dynatrace LLC. Permission is hereby granted,
// free of charge, to any person obtaining a copy of the Software,
// to view and use the Software for internal, non-production,
// non-commercial purposes only – the Software may not be used to
// process live data or distributed, sublicensed, modified and/or
// sold either alone or as part of or in combination with any other
// software.
//
// The above copyright notice and this permission notice shall be
// included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
// OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
// HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
// WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
// DEALINGS IN THE SOFTWARE.
//
package com.dynatrace.exaloglogpaper;

import static com.dynatrace.exaloglogpaper.DistinctCountUtil.*;
import static java.util.Objects.requireNonNull;

import com.dynatrace.hash4j.distinctcount.StateChangeObserver;
import com.dynatrace.hash4j.util.PackedArray;
import java.util.Arrays;

/** ExaLogLog sketch. */
public class ExaLogLog {

  /** Bias-reduced maximum-likelihood estimator. */
  public static final Estimator MAXIMUM_LIKELIHOOD_ESTIMATOR = new MaximumLikelihoodEstimator();

  /** The default estimator. */
  public static final Estimator DEFAULT_ESTIMATOR = MAXIMUM_LIKELIHOOD_ESTIMATOR;

  private static final int MAX_T = 58; // 64 - 6
  private static final int MIN_P = 2;

  private final byte p;
  private final byte t;
  private final byte d;

  private final byte[] state;

  private ExaLogLog(byte t, byte d, byte p, byte[] state) {
    this.t = t;
    this.d = d;
    this.p = p;
    this.state = state;
  }

  private static void checkTParameter(int t) {
    if (t < 0 || t > MAX_T) {
      throw new IllegalArgumentException("illegal T parameter");
    }
  }

  private static void checkDParameter(int d, int t) {
    if (d < 0 || d > getMaxD(t)) {
      throw new IllegalArgumentException("illegal D parameter");
    }
  }

  // visible for testing
  static void checkPrecisionParameter(int p, int minP, int maxP) {
    if (p < minP || p > maxP) {
      throw new IllegalArgumentException("illegal precision parameter");
    }
  }

  /**
   * Creates an empty ExaLogLog sketch.
   *
   * @param t the t-parameter
   * @param d the d-parameter
   * @param p the precision parameter
   * @return a new ExaLogLog sketch
   */
  public static final ExaLogLog create(int t, int d, int p) {
    checkTParameter(t);
    checkDParameter(d, t);
    checkPrecisionParameter(p, getMinP(), getMaxP(t));
    return new ExaLogLog(
        (byte) t,
        (byte) d,
        (byte) p,
        PackedArray.getHandler(getRegisterBitSize(t, d)).create(getNumRegisters(p)));
  }

  /**
   * Returns the t-parameter.
   *
   * @return the t-parameter
   */
  public int getT() {
    return t;
  }

  /**
   * Returns the d-parameter.
   *
   * @return the d-parameter
   */
  public int getD() {
    return d;
  }

  private static int getDMask(int d) {
    return (1 << d) - 1;
  }

  private static int getTMask(int t) {
    return (1 << t) - 1;
  }

  private static int getTMask2(int t) {
    return (1 << (1 << t)) - 1;
  }

  private static int getNumRegisters(int p) {
    return 1 << p;
  }

  /**
   * Returns the maximum possible p-parameter for a given t-parameter.
   *
   * @param t-parameter
   * @return maximum possible p-parameter
   */
  public static final int getMaxP(int t) {
    checkTParameter(t);
    return 26 - t;
  }

  /**
   * Returns the minimum possible p-parameter.
   *
   * @return minimum possible p-parameter
   */
  public static final int getMinP() {
    return MIN_P;
  }

  /**
   * Returns the maximum possible t-parameter.
   *
   * @return maximum possible t-parameter
   */
  public static final int getMaxT() {
    return MAX_T;
  }

  /**
   * Returns the maximum possible d-parameter for a given t-parameter.
   *
   * @param t-parameter
   * @return maximum possible d-parameter
   */
  public static final int getMaxD(int t) {
    return 64 - 6 - t;
  }

  static int getRegisterBitSize(int t, int d) {
    return 6 + t + d;
  }

  private long getRegister(int idx) {
    return getPackedArrayHandler().get(state, idx);
  }

  private void setRegister(int idx, long newValue) {
    getPackedArrayHandler().set(state, idx, newValue);
  }

  private PackedArray.PackedArrayHandler getPackedArrayHandler() {
    return PackedArray.getHandler(getRegisterBitSize(t, d));
  }

  /**
   * Returns a ExaLogLog sketch whose state is kept in the given byte array.
   *
   * <p>If the state is not valid (it was not retrieved using {@link #getState()} and the
   * corresponding t- and d-parameters were different) the behavior will be undefined.
   *
   * @param t the t-parameter
   * @param d the d-parameter
   * @param state the state
   * @return the new sketch
   * @throws NullPointerException if the passed array is null
   * @throws IllegalArgumentException if the passed array has invalid length
   */
  public static ExaLogLog wrap(int t, int d, byte[] state) {
    requireNonNull(state, "null argument");
    long regBitSize = getRegisterBitSize(t, d);
    int m = (int) ((((long) state.length) << 3) / regBitSize);
    int p = 31 - Integer.numberOfLeadingZeros(m);
    if (p < MIN_P || p > getMaxP(t) || (((regBitSize << p) + 7) >>> 3) != state.length) {
      throw getUnexpectedStateLengthException();
    }
    return new ExaLogLog((byte) t, (byte) d, (byte) p, state);
  }

  /**
   * Merges two {@link ExaLogLog} sketches into a new sketch.
   *
   * <p>The precision of the merged sketch is given by the smaller precision of both sketches.
   *
   * @param sketch1 the first sketch
   * @param sketch2 the second sketch
   * @return the merged sketch
   * @throws NullPointerException if one of both arguments is null
   */
  public static ExaLogLog merge(ExaLogLog sketch1, ExaLogLog sketch2) {
    requireNonNull(sketch1, "first sketch was null");
    requireNonNull(sketch2, "second sketch was null");
    if (sketch1.t != sketch2.t) {
      throw new IllegalArgumentException("t-parameter is not equal");
    }
    if (sketch1.p <= sketch2.p) {
      if (sketch1.d <= sketch2.d) {
        return sketch1.copy().add(sketch2);
      } else {
        return sketch1.downsize(sketch2.d, sketch1.p).add(sketch2);
      }
    } else {
      if (sketch1.d >= sketch2.d) {
        return sketch2.copy().add(sketch1);
      } else {
        return sketch2.downsize(sketch1.d, sketch2.p).add(sketch1);
      }
    }
  }

  /**
   * Adds a new element represented by a 64-bit hash value to this sketch.
   *
   * <p>In order to get good estimates, it is important that the hash value is calculated using a
   * high-quality hash algorithm.
   *
   * @param hashValue a 64-bit hash value
   * @return this sketch
   */
  public ExaLogLog add(long hashValue) {
    add(hashValue, null);
    return this;
  }

  /**
   * Computes a token from a given 64-bit hash value.
   *
   * <p>Instead of updating the sketch with the hash value using the {@link #add(long)} method, it
   * can alternatively be updated with the corresponding 32-bit token using the {@link
   * #addToken(int)} method.
   *
   * <p>{@code addToken(computeToken(hash))} is equivalent to {@code add(hash)}
   *
   * <p>Tokens can be temporarily collected using for example an {@code int[] array} and added later
   * using {@link #addToken(int)} into the sketch resulting exactly in the same final state. This
   * can be used to realize a sparse mode, where the sketch is created only when there are enough
   * tokens to justify the memory allocation. It is sufficient to store only distinct tokens.
   * Deduplication does not result in any loss of information with respect to distinct count
   * estimation.
   *
   * @param hashValue the 64-bit hash value
   * @return the 32-bit token
   */
  public static int computeToken(long hashValue) {
    return DistinctCountUtil.computeToken(hashValue);
  }

  /**
   * Adds a new element represented by a 32-bit token obtained from {@code computeToken(long)}.
   *
   * <p>{@code addToken(computeToken(hash))} is equivalent to {@code add(hash)}
   *
   * @param token a 32-bit hash token
   * @return this sketch
   */
  public ExaLogLog addToken(int token) {
    return add(DistinctCountUtil.reconstructHash(token));
  }

  /**
   * Returns an estimate of the number of distinct elements added to this sketch.
   *
   * @return estimated number of distinct elements
   */
  public double getDistinctCountEstimate() {
    return getDistinctCountEstimate(DEFAULT_ESTIMATOR);
  }

  /**
   * Returns an estimate of the number of distinct elements added to this sketch using the given
   * estimator.
   *
   * @param estimator the estimator
   * @return estimated number of distinct elements
   */
  public double getDistinctCountEstimate(Estimator estimator) {
    return estimator.estimate(this);
  }

  /**
   * Creates a copy of this sketch.
   *
   * @return the copy
   */
  public ExaLogLog copy() {
    return new ExaLogLog(t, d, p, Arrays.copyOf(state, state.length));
  }

  private static long shiftRight(long s, long delta) {
    if (delta < 64) {
      return s >>> delta;
    } else {
      return 0;
    }
  }

  private static long computeDownsizeThresholdU(int t, int fromP) {
    return ((64L - t - fromP) << t) + 1;
  }

  private static long downsizeRegister(
      long r, int t, int fromD, int toD, int fromP, int toP, int subIdx, long downsizeThresholdU) {
    long u = r >>> fromD;
    r >>>= fromD - toD;
    if (u >= downsizeThresholdU) {
      long shift = ((fromP - toP) - (32 - Integer.numberOfLeadingZeros(subIdx))) << t;
      if (shift > 0) {
        long numBitsToShift = toD + downsizeThresholdU - u;
        if (numBitsToShift > 0) {
          long mask = 0xFFFFFFFFFFFFFFFFL << numBitsToShift;
          r = (mask & r) | shiftRight((r & ~mask), shift);
        }
        r += shift << toD;
      }
    }
    return r;
  }

  private static long mergeRegister(long r1, long r2, int d) {
    long u1 = r1 >>> d;
    long u2 = r2 >>> d;
    if (u1 > u2 && u2 > 0) {
      long x = 1L << d;
      return r1 | shiftRight(x | (r2 & (x - 1)), u1 - u2);
    } else if (u2 > u1 && u1 > 0) {
      long x = 1L << d;
      return r2 | shiftRight(x | (r1 & (x - 1)), u2 - u1);
    } else {
      return r1 | r2;
    }
  }

  /**
   * Adds another sketch.
   *
   * <p>The precision parameter of the added sketch must not be smaller than the precision parameter
   * of this sketch. Otherwise, an {@link IllegalArgumentException} will be thrown.
   *
   * @param other the other sketch
   * @return this sketch
   * @throws NullPointerException if the argument is null
   */
  public ExaLogLog add(ExaLogLog other) {
    requireNonNull(other, "null argument");
    if (other.t != t) {
      throw new IllegalArgumentException(
          "merging of ExaLogLog sketches with different t-parameter is not possible");
    }
    if (other.d < d) {
      throw new IllegalArgumentException("other has smaller d-parameter");
    }
    if (other.p < p) {
      throw new IllegalArgumentException("other has smaller precision");
    }
    final int m = getNumRegisters(p);
    final int maxSubIndex = 1 << (other.p - p);
    final long downsizeThresholdU = computeDownsizeThresholdU(t, other.p);
    for (int registerIndex = 0; registerIndex < m; ++registerIndex) {
      long mergedR =
          downsizeRegister(
              other.getRegister(registerIndex), t, other.d, d, other.p, p, 0, downsizeThresholdU);
      for (int subIndex = 1; subIndex < maxSubIndex; ++subIndex) {
        long otherR =
            downsizeRegister(
                other.getRegister(registerIndex + (subIndex << p)),
                t,
                other.d,
                d,
                other.p,
                p,
                subIndex,
                downsizeThresholdU);
        mergedR = mergeRegister(mergedR, otherR, d);
      }
      if (mergedR != 0) {
        final long thisR = getRegister(registerIndex);
        mergedR = mergeRegister(mergedR, thisR, d);
        if (thisR != mergedR) {
          setRegister(registerIndex, mergedR);
        }
      }
    }
    return this;
  }

  /**
   * Returns a downsized copy of this sketch with a precision that is not larger than the given
   * precision parameter.
   *
   * @param d the d-parameter used for downsizing
   * @param p the precision parameter used for downsizing
   * @return the downsized copy
   * @throws IllegalArgumentException if the precision parameter is invalid
   */
  public ExaLogLog downsize(int d, int p) {
    checkPrecisionParameter(p, getMinP(), getMaxP(t));
    checkDParameter(d, t);
    if (p >= this.p && d >= this.d) {
      return copy();
    } else {
      return create(t, d, p).add(this);
    }
  }

  /** A distinct count estimator for ExaLogLog. */
  public interface Estimator {
    /**
     * Estimates the number of distinct elements added to the given sketch.
     *
     * @param sketch the sketch
     * @return estimated number of distinct elements
     */
    double estimate(ExaLogLog sketch);
  }

  /**
   * Resets this sketch to its initial state representing an empty set.
   *
   * @return this sketch
   */
  public ExaLogLog reset() {
    Arrays.fill(state, (byte) 0);
    return this;
  }

  /**
   * Returns a reference to the internal state of this sketch.
   *
   * <p>The returned state is never {@code null}.
   *
   * @return the internal state of this sketch
   */
  public byte[] getState() {
    return state;
  }

  /**
   * Returns the precision parameter of this sketch.
   *
   * @return the precision parameter
   */
  public int getP() {
    return p;
  }

  /**
   * Adds a new element represented by a 64-bit hash value to this sketch and passes, if the
   * internal state has changed, decrements of the state change probability to the given {@link
   * StateChangeObserver}.
   *
   * <p>In order to get good estimates, it is important that the hash value is calculated using a
   * high-quality hash algorithm.
   *
   * @param hashValue a 64-bit hash value
   * @param stateChangeObserver a state change observer
   * @return this sketch
   */
  public ExaLogLog add(long hashValue, StateChangeObserver stateChangeObserver) {
    int idx = (int) ((hashValue >>> t) & ((1L << p) - 1));
    int sub = (int) hashValue & getTMask(t);
    int nlz =
        Long.numberOfLeadingZeros(hashValue | (((1L << p) << t) - 1)); // nlz in {0, 1, ..., 64-p-t}

    long updateVal = (nlz << t) + sub + 1; // in the range [1, 252] for t==2
    long currentVal = getRegister(idx);
    long currentMax = currentVal >>> d;
    long delta = updateVal - currentMax;
    long newVal;
    if (delta > 0) {
      newVal = updateVal << d;
      if (delta <= d) {
        newVal |= ((currentVal & getDMask(d)) | (1L << d)) >>> delta;
      }
    } else {
      newVal = currentVal;
      if (delta < 0 && d + delta >= 0) {
        newVal |= (1L << (d + delta));
      }
    }
    if (newVal != currentVal) {
      setRegister(idx, newVal);
      if (stateChangeObserver != null) {
        stateChangeObserver.stateChanged(
            getRegisterChangeProbability(currentVal) - getRegisterChangeProbability(newVal));
      }
    }
    return this;
  }

  /**
   * Adds a new element, represented by a 32-bit token obtained from {@code computeToken(long)}, to
   * this sketch and passes, if the internal state has changed, decrements of the state change
   * probability to the given {@link StateChangeObserver}.
   *
   * <p>{@code addToken(computeToken(hash), stateChangeObserver)} is equivalent to {@code add(hash,
   * stateChangeObserver)}
   *
   * @param token a 32-bit hash token
   * @param stateChangeObserver a state change observer
   * @return this sketch
   */
  public ExaLogLog addToken(int token, StateChangeObserver stateChangeObserver) {
    return add(DistinctCountUtil.reconstructHash(token), stateChangeObserver);
  }

  /**
   * Returns the probability of an internal state change when a new distinct element is added.
   *
   * @return the state change probability
   */
  public double getStateChangeProbability() {
    double sum = 0;
    int m = getNumRegisters(p);
    for (int idx = 0; idx < m; ++idx) {
      long r = getRegister(idx);
      sum += getRegisterChangeProbability(r);
    }
    return sum;
  }

  private double getRegisterChangeProbability(long r) {
    int u = (int) (r >>> d);
    if (u == 0) {
      return Double.longBitsToDouble((0x3FFL - p) << 52);
    } else {
      int q = 63 - t - p;
      int j = (u - 1) >>> t;
      int i = Math.min(q, j);
      long a = (long) (((i + 2) << t) - u) << (q - i);
      int rReducedInv = (int) ~r & getDMask(d);
      int mask = getTMask2(t) << (d - 1 - ((u - 2) & getTMask(t)));
      j = (u - 2) >> t;
      while (j >= 0 && mask != 0) {
        i = Math.min(q, j);
        a += (long) Integer.bitCount(mask & rReducedInv) << (q - i);
        mask >>>= 1 << t;
        j -= 1;
      }
      return a * 0x1p-64;
    }
  }

  private long contribute(int r, int[] b, int p) {
    int u = r >>> d;
    if (u == 0) {
      return 1L << -p;
    } else {
      int q = 63 - t - p;
      int j = (u - 1) >>> t;
      int i = Math.min(q, j);
      long a = (long) (((i + 2) << t) - u) << (q - i);
      b[i] += 1;
      int dMAsk = getDMask(d);
      int rReduced = r & dMAsk;
      int rReducedInv = ~r & dMAsk;
      int mask = getTMask2(t) << (d - 1 - ((u - 2) & getTMask(t)));
      j = (u - 2) >> t;
      while (j >= 0 && mask != 0) {
        i = Math.min(q, j);
        a += (long) Integer.bitCount(mask & rReducedInv) << (q - i);
        b[i] += Integer.bitCount(mask & rReduced);
        mask >>>= 1 << t;
        j -= 1;
      }
      return a;
    }
  }

  private static final class MaximumLikelihoodEstimator implements Estimator {

    @Override
    public double estimate(ExaLogLog sketch) {
      int p = sketch.p;
      int t = sketch.t;
      int d = sketch.d;
      int m = getNumRegisters(p);

      long agg = 0;
      int[] b = new int[64];
      for (int idx = 0; idx < m; idx += 1) {
        agg += sketch.contribute((int) sketch.getRegister(idx), b, p);
      }
      if (agg == 0) {
        return (b[63 - t - p] == 0) ? 0 : Double.POSITIVE_INFINITY;
      }

      double factor = m << (t + 1);
      double a = unsignedLongToDouble(agg) * 0x1p-64 * factor;

      return factor
          * DistinctCountUtil.solveMaximumLikelihoodEquation(a, b, 63 - p - t, 1e-3 / Math.sqrt(m))
          / (1 + ML_BIAS_CORRECTION_CONSTANTS[t][d] / m);
    }
  }

  // visible for testing
  static final double[][] ML_BIAS_CORRECTION_CONSTANTS = {
    {
      1.01015908095854,
      0.6574064986454711,
      0.48147376527720065,
      0.3941928266965642,
      0.35089447879078073,
      0.32936465981278,
      0.318634958796536,
      0.3132796686731958,
      0.310604514513206,
      0.30926757315551373,
      0.3085992630572626,
      0.30826514836114693,
      0.30809810112742536,
      0.3080145800424155,
      0.30797282013328203,
      0.3079519403371093,
      0.30794150047862784,
      0.3079362805592891,
      0.30793367060209537,
      0.30793236562411735,
      0.3079317131352831,
      0.30793138689090466,
      0.3079312237687251,
      0.3079311422076378,
      0.3079311014270947,
      0.3079310810368233,
      0.30793107084168764,
      0.3079310657441198,
      0.3079310631953359,
      0.307931061920944,
      0.307931061283748,
      0.30793106096515,
      0.30793106080585103,
      0.3079310607262015,
      0.30793106068637677,
      0.3079310606664644,
      0.3079310606565082,
      0.30793106065153014,
      0.3079310606490411,
      0.3079310606477966,
      0.3079310606471743,
      0.30793106064686315,
      0.3079310606467076,
      0.3079310606466298,
      0.3079310606465909,
      0.3079310606465715,
      0.3079310606465617,
      0.3079310606465569,
      0.30793106064655446,
      0.30793106064655323,
      0.3079310606465526,
      0.30793106064655235,
      0.3079310606465522,
      0.3079310606465521,
      0.30793106064655207,
      0.30793106064655207,
      0.307931060646552,
      0.307931060646552,
      0.307931060646552
    },
    {
      1.0008872152347768,
      0.7535432119395638,
      0.5779922482607894,
      0.45345476873253704,
      0.36524045688604806,
      0.30288772510110057,
      0.2589033693092638,
      0.2279182490676776,
      0.2061024478499015,
      0.19074089378272704,
      0.17991875444163985,
      0.17228968432095998,
      0.16690809112853044,
      0.16310971904232568,
      0.16042754724297184,
      0.15853287628721133,
      0.1571941223746893,
      0.15624797993008271,
      0.15557920923487562,
      0.15510644463518605,
      0.15477221384540174,
      0.1545359092681694,
      0.15436883289220926,
      0.15425070016897735,
      0.1541671717835069,
      0.15410811033016417,
      0.1540663485940499,
      0.15403681909656813,
      0.1540159388434168,
      0.15400117440223882,
      0.1539907344294835,
      0.15398335228581844,
      0.1539781323379076,
      0.1539744412853102,
      0.15397183132097297,
      0.15396998579948357,
      0.15396868081971968,
      0.15396775806017737,
      0.15396710557089663,
      0.15396664419142608,
      0.15396631794693602,
      0.1539660872572759,
      0.15396592413506843,
      0.15396580879025717,
      0.15396572722916282,
      0.15396566955676189,
      0.15396562877621708,
      0.15396559994001777,
      0.15396557954974596,
      0.1539655651316466,
      0.15396555493651085,
      0.15396554772746124,
      0.1539655426298934,
      0.1539655390253686,
      0.1539655364765847,
      0.1539655346743223,
      0.15396553339993035,
      0.15396553249879916
    },
    {
      1.0000622933078316,
      0.8539547269903729,
      0.7309857307864877,
      0.6274683312575394,
      0.5403052852252049,
      0.4668961218056207,
      0.40505866032132765,
      0.3529624889872381,
      0.30907237397400117,
      0.2721000490382485,
      0.24096326105437188,
      0.21475125005795784,
      0.19269598704624133,
      0.1741485011252804,
      0.15855957138424717,
      0.14546401855336089,
      0.13446785617432022,
      0.12523765453607028,
      0.11749160485613996,
      0.11099190981733918,
      0.1055382430717344,
      0.1009621038377,
      0.09712194493314381,
      0.09389898147950879,
      0.09119360205952269,
      0.08892231147244983,
      0.08701513896044305,
      0.08541345020877446,
      0.0840681063973968,
      0.08293791914655979,
      0.08198835607360877,
      0.08119045752259442,
      0.08051993056768486,
      0.07995639144859477,
      0.07948273208488091,
      0.07908459021739993,
      0.07874990606483087,
      0.07846855121070934,
      0.07823201781223714,
      0.07803315820824429,
      0.07786596665871105,
      0.07772539632492277,
      0.07760720574354613,
      0.07750782999885664,
      0.07742427258796859,
      0.07735401463170538,
      0.07729493863148885,
      0.0772452644291579,
      0.07720349540750207,
      0.07716837328734202,
      0.07713884014280341,
      0.07711400647876561,
      0.07709312440056891,
      0.077075565061937,
      0.0770607997076996,
      0.07704838373742592,
      0.07703794330795105
    },
    {
      1.0000040238681394,
      0.9204987904254645,
      0.8475836592095825,
      0.7807110942115162,
      0.7193789855218202,
      0.6631268838856164,
      0.6115325490668426,
      0.5642087861144511,
      0.5208005457444387,
      0.4809822669778773,
      0.4444554419193146,
      0.4109463841348844,
      0.38020418350915014,
      0.35199883174001806,
      0.3261195037908379,
      0.3023729816801102,
      0.280582207977136,
      0.2605849573132797,
      0.24223261513950523,
      0.2253890538838387,
      0.20992959760207305,
      0.195740067174871,
      0.18271589907444907,
      0.1707613316806586,
      0.1597886540348692,
      0.14971751274009065,
      0.14047427340789412,
      0.1319914335862557,
      0.12420708446178994,
      0.11706441881797963,
      0.1105112827697397,
      0.10449976872115586,
      0.09898584685397362,
      0.09392903229774886,
      0.08929208500174565,
      0.0850407392563939,
      0.08114345981765922,
      0.07757122167649293,
      0.07429731068114112,
      0.07129714244734374,
      0.0685480972603452,
      0.06602936896195191,
      0.06372182610607409,
      0.06160788394135548,
      0.05967138602823457,
      0.057897494513335486,
      0.056272588263823094,
      0.05478416820891764,
      0.053420769348107654,
      0.05217187897006696,
      0.051027860687819525,
      0.0499798839392591,
      0.04901985863228224,
      0.04814037463446698,
      0.04733464582163703,
      0.04659645841027936
    },
    {
      1.000000253653387,
      0.9585085176365462,
      0.9187753280286737,
      0.8807261054085846,
      0.8442894314848283,
      0.8093969150597714,
      0.7759830636776742,
      0.7439851607160006,
      0.7133431476894436,
      0.6839995115459382,
      0.6558991767433208,
      0.6289894019042789,
      0.6032196808558455,
      0.5785416478679312,
      0.554908986913284,
      0.5322773447788278,
      0.510604247865566,
      0.48984902252117307,
      0.46997271875603,
      0.45093803719981496,
      0.4327092591618419,
      0.41525217966415856,
      0.39853404332198367,
      0.38252348295138966,
      0.3671904607892282,
      0.35250621221516365,
      0.33844319187032934,
      0.3249750220715597,
      0.31207644342438984,
      0.29972326754205253,
      0.2878923317815529,
      0.27656145591156733,
      0.265709400630401,
      0.255315827855554,
      0.24536126270959824,
      0.23582705713005736,
      0.2266953550338219,
      0.21794905896932634,
      0.2095717981922721,
      0.2015478981031094,
      0.19386235098680218,
      0.1865007879976012,
      0.17944945233365692,
      0.1726951735483226,
      0.16622534294694616,
      0.1600278900198382,
      0.1540912598639463,
      0.14840439154757762,
      0.14295669737430328,
      0.13773804300396456,
      0.13273872839048975,
      0.1279494694980335,
      0.12336138075877205,
      0.11896595823753295,
      0.11475506347030666
    },
    {
      1.0000000158876303,
      0.978802468145575,
      0.9580591039586601,
      0.9377601903482514,
      0.9178962028781065,
      0.8984578211854727,
      0.8794359246079413,
      0.8608215879040062,
      0.8426060770653196,
      0.8247808452186803,
      0.8073375286158306,
      0.7902679427091824,
      0.7735640783116295,
      0.7572180978386451,
      0.7412223316309017,
      0.7255692743556881,
      0.7102515814854349,
      0.6952620658516967,
      0.6805936942729739,
      0.6662395842547927,
      0.6521930007604949,
      0.6384473530512225,
      0.6249961915936147,
      0.611833205033767,
      0.5989522172360323,
      0.5863471843852752,
      0.5740121921512205,
      0.5619414529135647,
      0.5501293030465505,
      0.5385702002617291,
      0.527258721007664,
      0.5161895579253584,
      0.5053575173582092,
      0.49475751691532505,
      0.48438458308705873,
      0.47423384891164216,
      0.46430055169182527,
      0.45458003076044995,
      0.44506772529391025,
      0.4357591721724744,
      0.4266500038864641,
      0.4177359464873109,
      0.40901281758252656,
      0.40047652437365,
      0.39212306173624895,
      0.3839485103410765,
      0.3759490348155029,
      0.36812088194435855,
      0.36046037890934735,
      0.35296393156620265,
      0.3456280227587804,
      0.33844921066929784,
      0.33142412720394504,
      0.32454947641311327
    },
    {
      1.0000000009935148,
      0.9892861356619387,
      0.9786876776673491,
      0.9682033838190588,
      0.9578320243177585,
      0.9475723826112629,
      0.9374232552518098,
      0.9273834517548972,
      0.9174517944596408,
      0.9076271183906346,
      0.8979082711213014,
      0.8882941126387138,
      0.878783515209872,
      0.8693753632494214,
      0.8600685531887965,
      0.850861993346772,
      0.8417546038014113,
      0.8327453162633914,
      0.8238330739506945,
      0.8150168314646481,
      0.8062955546673014,
      0.7976682205601213,
      0.7891338171639963,
      0.7806913434005323,
      0.772339808974627,
      0.7640782342583097,
      0.755905650175832,
      0.7478210980899959,
      0.7398236296897074,
      0.7319123068787401,
      0.724086201665698,
      0.7163443960551632,
      0.7086859819400156,
      0.701110060994914,
      0.6936157445709228,
      0.6862021535912748,
      0.6788684184482571,
      0.6716136789012064,
      0.6644370839756046,
      0.6573377918632602,
      0.6503149698235656,
      0.6433677940858175,
      0.6364954497525896,
      0.6296971307041472,
      0.622972039503889,
      0.6163193873048105,
      0.6097383937569721,
      0.6032282869159661,
      0.5967883031523683,
      0.5904176870621657,
      0.5841156913781488,
      0.577881576882258,
      0.5717146123188753
    },
    {
      1.000000000062103,
      0.9946140197506017,
      0.9892571266949817,
      0.9839291638065226,
      0.9786299748448696,
      0.9733594044134515,
      0.9681172979549245,
      0.962903501746639,
      0.9577178628961321,
      0.9525602293366445,
      0.9474304498226606,
      0.9423283739254735,
      0.9372538520287738,
      0.9322067353242621,
      0.9271868758072853,
      0.9221941262724965,
      0.9172283403095381,
      0.9122893722987484,
      0.9073770774068914,
      0.9024913115829097,
      0.8976319315537004,
      0.8927987948199128,
      0.887991759651771,
      0.883210685084917,
      0.8784554309162766,
      0.873725857699949,
      0.8690218267431168,
      0.864343200101979,
      0.859689840577706,
      0.8550616117124169,
      0.8504583777851765,
      0.8458800038080166,
      0.8413263555219772,
      0.836797299393169,
      0.8322927026088582,
      0.8278124330735713,
      0.8233563594052217,
      0.8189243509312568,
      0.8145162776848267,
      0.8101320104009719,
      0.8057714205128337,
      0.8014343801478833,
      0.7971207621241725,
      0.7928304399466036,
      0.7885632878032206,
      0.7843191805615195,
      0.7800979937647787,
      0.7758996036284096,
      0.7717238870363266,
      0.7675707215373365,
      0.7634399853415484,
      0.7593315573168016
    },
    {
      1.0000000000038816,
      0.9972997133909425,
      0.994606728192613,
      0.9919210246662532,
      0.9892425831226063,
      0.9865713839256541,
      0.983907407492473,
      0.9812506342930903,
      0.9786010448503416,
      0.975958619739727,
      0.9733233395892699,
      0.9706951850793738,
      0.9680741369426817,
      0.9654601759639343,
      0.962853282979829,
      0.9602534388788797,
      0.9576606246012768,
      0.9550748211387471,
      0.9524960095344147,
      0.949924170882662,
      0.947359286328991,
      0.9448013370698853,
      0.9422503043526718,
      0.9397061694753835,
      0.9371689137866229,
      0.9346385186854242,
      0.9321149656211178,
      0.9295982360931939,
      0.9270883116511671,
      0.9245851738944407,
      0.9220888044721725,
      0.9195991850831395,
      0.9171162974756045,
      0.9146401234471816,
      0.9121706448447032,
      0.9097078435640868,
      0.9072517015502021,
      0.9048022007967389,
      0.902359323346075,
      0.8999230512891445,
      0.8974933667653066,
      0.8950702519622143,
      0.8926536891156848,
      0.8902436605095676,
      0.8878401484756165,
      0.8854431353933587,
      0.8830526036899663,
      0.8806685358401269,
      0.8782909143659162,
      0.8759197218366684,
      0.8735549408688497
    },
    {
      1.0000000000002427,
      0.9986480282491883,
      0.9972978855627284,
      0.995949569466344,
      0.9946030774888643,
      0.9932584071624618,
      0.9919155560226471,
      0.9905745216082656,
      0.9892353014614922,
      0.9878978931278266,
      0.9865622941560896,
      0.9852285020984181,
      0.9838965145102604,
      0.9825663289503722,
      0.981237942980812,
      0.9799113541669365,
      0.9785865600773962,
      0.9772635582841307,
      0.9759423463623649,
      0.974622921890604,
      0.9733052824506289,
      0.9719894256274925,
      0.9706753490095147,
      0.9693630501882781,
      0.9680525267586235,
      0.9667437763186459,
      0.9654367964696896,
      0.9641315848163441,
      0.9628281389664394,
      0.9615264565310422,
      0.9602265351244508,
      0.9589283723641913,
      0.957631965871013,
      0.9563373132688839,
      0.9550444121849869,
      0.9537532602497145,
      0.9524638550966653,
      0.9511761943626396,
      0.9498902756876342,
      0.9486060967148394,
      0.9473236550906338,
      0.9460429484645798,
      0.9447639744894201,
      0.9434867308210729,
      0.9422112151186276,
      0.9409374250443405,
      0.9396653582636308,
      0.9383950124450761,
      0.9371263852604079,
      0.9358594743845079
    },
    {
      1.000000000000015,
      0.9993235564713114,
      0.9986475706733046,
      0.9979720422962609,
      0.9972969710306561,
      0.9966223565671753,
      0.9959481985967134,
      0.9952744968103737,
      0.9946012508994692,
      0.9939284605555214,
      0.9932561254702607,
      0.992584245335626,
      0.9919128198437646,
      0.9912418486870324,
      0.9905713315579932,
      0.9899012681494189,
      0.9892316581542894,
      0.9885625012657923,
      0.9878937971773225,
      0.987225545582483,
      0.9865577461750836,
      0.9858903986491415,
      0.9852235026988809,
      0.984557058018733,
      0.9838910643033356,
      0.9832255212475333,
      0.9825604285463772,
      0.9818957858951245,
      0.9812315929892389,
      0.98056784952439,
      0.9799045551964534,
      0.9792417097015105,
      0.9785793127358483,
      0.9779173639959594,
      0.9772558631785417,
      0.9765948099804984,
      0.9759342040989377,
      0.9752740452311728,
      0.9746143330747217,
      0.9739550673273072,
      0.9732962476868566,
      0.9726378738515015,
      0.9719799455195777,
      0.9713224623896254,
      0.9706654241603887,
      0.9700088305308154,
      0.9693526812000571,
      0.9686969758674691,
      0.9680417142326099
    },
    {
      1.0000000000000009,
      0.9996616637545549,
      0.9993234419998942,
      0.9989853346972759,
      0.9986473418079703,
      0.9983094632932604,
      0.997971699114443,
      0.9976340492328271,
      0.9972965136097355,
      0.9969590922065039,
      0.996621784984481,
      0.9962845919050286,
      0.9959475129295217,
      0.995610548019348,
      0.9952736971359087,
      0.9949369602406181,
      0.9946003372949029,
      0.9942638282602037,
      0.9939274330979736,
      0.9935911517696788,
      0.9932549842367988,
      0.9929189304608259,
      0.9925829904032655,
      0.992247164025636,
      0.9919114512894689,
      0.9915758521563086,
      0.9912403665877126,
      0.9909049945452515,
      0.9905697359905087,
      0.9902345908850807,
      0.9898995591905769,
      0.98956464086862,
      0.9892298358808452,
      0.9888951441889011,
      0.9885605657544492,
      0.9882261005391637,
      0.9878917485047322,
      0.9875575096128547,
      0.9872233838252449,
      0.9868893711036287,
      0.9865554714097455,
      0.9862216847053474,
      0.9858880109521996,
      0.98555445011208,
      0.9852210021467794,
      0.984887667018102,
      0.9845544446878646,
      0.9842213351178967
    },
    {
      1.0,
      0.9998308032485256,
      0.999661635127014,
      0.9994924956306209,
      0.9993233847545024,
      0.9991543024938158,
      0.998985248843719,
      0.9988162237993706,
      0.9986472273559305,
      0.998478259508559,
      0.9983093202524171,
      0.9981404095826671,
      0.9979715274944719,
      0.997802673982995,
      0.997633849043401,
      0.9974650526708553,
      0.9972962848605238,
      0.9971275456075737,
      0.9969588349071726,
      0.9967901527544892,
      0.996621499144693,
      0.996452874072954,
      0.9962842775344434,
      0.9961157095243329,
      0.9959471700377954,
      0.9957786590700043,
      0.9956101766161339,
      0.9954417226713593,
      0.9952732972308566,
      0.9951049002898023,
      0.9949365318433742,
      0.9947681918867505,
      0.9945998804151106,
      0.9944315974236344,
      0.9942633429075028,
      0.9940951168618976,
      0.9939269192820009,
      0.9937587501629963,
      0.9935906095000677,
      0.9934224972884003,
      0.9932544135231796,
      0.9930863581995921,
      0.9929183313128254,
      0.9927503328580675,
      0.9925823628305074,
      0.9924144212253349,
      0.9922465080377406
    },
    {
      1.0,
      0.999915394466015,
      0.9998307960904292,
      0.999746204872637,
      0.9996616208120327,
      0.9995770439080107,
      0.9994924741599657,
      0.9994079115672919,
      0.9993233561293842,
      0.9992388078456371,
      0.9991542667154454,
      0.9990697327382037,
      0.9989852059133068,
      0.9989006862401496,
      0.998816173718127,
      0.9987316683466341,
      0.9986471701250657,
      0.9985626790528167,
      0.9984781951292826,
      0.9983937183538583,
      0.9983092487259391,
      0.9982247862449202,
      0.9981403309101968,
      0.9980558827211645,
      0.9979714416772186,
      0.9978870077777546,
      0.9978025810221679,
      0.9977181614098541,
      0.997633748940209,
      0.9975493436126279,
      0.9974649454265068,
      0.9973805543812414,
      0.9972961704762275,
      0.997211793710861,
      0.9971274240845378,
      0.9970430615966538,
      0.9969587062466052,
      0.9968743580337879,
      0.9967900169575982,
      0.996705683017432,
      0.9966213562126858,
      0.9965370365427557,
      0.9964527240070382,
      0.9963684186049295,
      0.9962841203358263,
      0.9961998291991246
    },
    {
      1.0,
      0.999957695443313,
      0.9999153926763394,
      0.9998730916990036,
      0.9998307925112296,
      0.9997884951129419,
      0.9997461995040647,
      0.9997039056845224,
      0.9996616136542391,
      0.9996193234131393,
      0.9995770349611474,
      0.9995347482981873,
      0.9994924634241837,
      0.9994501803390607,
      0.9994078990427429,
      0.9993656195351543,
      0.9993233418162194,
      0.9992810658858625,
      0.9992387917440079,
      0.99919651939058,
      0.999154248825503,
      0.9991119800487014,
      0.9990697130600995,
      0.9990274478596216,
      0.9989851844471921,
      0.9989429228227353,
      0.9989006629861757,
      0.9988584049374376,
      0.9988161486764453,
      0.9987738942031231,
      0.9987316415173956,
      0.9986893906191869,
      0.9986471415084217,
      0.9986048941850242,
      0.9985626486489186,
      0.9985204049000297,
      0.9984781629382816,
      0.9984359227635987,
      0.9983936843759056,
      0.9983514477751264,
      0.9983092129611858,
      0.998266979934008,
      0.9982247486935175,
      0.9981825192396387,
      0.998140291572296
    },
    {
      1.0,
      0.9999788472742164,
      0.9999576949958753,
      0.9999365431649673,
      0.9999153917814828,
      0.9998942408454126,
      0.999873090356747,
      0.9998519403154765,
      0.999830790721592,
      0.9998096415750837,
      0.9997884928759422,
      0.9997673446241581,
      0.9997461968197218,
      0.999725049462624,
      0.9997039025528552,
      0.9996827560904059,
      0.9996616100752667,
      0.9996404645074279,
      0.9996193193868804,
      0.9995981747136146,
      0.9995770304876209,
      0.9995558867088901,
      0.9995347433774124,
      0.9995136004931786,
      0.9994924580561791,
      0.9994713160664046,
      0.9994501745238455,
      0.9994290334284924,
      0.9994078927803358,
      0.9993867525793664,
      0.9993656128255745,
      0.9993444735189506,
      0.9993233346594855,
      0.9993021962471696,
      0.9992810582819935,
      0.9992599207639478,
      0.9992387836930229,
      0.9992176470692093,
      0.9991965108924977,
      0.9991753751628786,
      0.9991542398803425,
      0.99913310504488,
      0.9991119706564815,
      0.9990908367151378
    },
    {
      1.0,
      0.9999894235252461,
      0.9999788471623545,
      0.9999682709113242,
      0.999957694772154,
      0.9999471187448425,
      0.9999365428293888,
      0.9999259670257916,
      0.9999153913340497,
      0.9999048157541619,
      0.9998942402861271,
      0.9998836649299441,
      0.9998730896856115,
      0.9998625145531285,
      0.9998519395324935,
      0.9998413646237057,
      0.9998307898267637,
      0.9998202151416663,
      0.9998096405684125,
      0.9997990661070009,
      0.9997884917574305,
      0.9997779175196999,
      0.9997673433938081,
      0.999756769379754,
      0.9997461954775362,
      0.9997356216871535,
      0.999725048008605,
      0.9997144744418891,
      0.999703900987005,
      0.9996933276439514,
      0.999682754412727,
      0.9996721812933308,
      0.9996616082857614,
      0.9996510353900179,
      0.9996404626060988,
      0.9996298899340031,
      0.9996193173737298,
      0.9996087449252773,
      0.9995981725886447,
      0.9995876003638307,
      0.9995770282508342,
      0.9995664562496539,
      0.9995558843602887
    },
    {
      1.0,
      0.9999947117346573,
      0.9999894234972803,
      0.9999841352878691,
      0.9999788471064234,
      0.9999735589529429,
      0.9999682708274279,
      0.9999629827298778,
      0.9999576946602927,
      0.9999524066186725,
      0.9999471186050168,
      0.9999418306193256,
      0.9999365426615988,
      0.9999312547318362,
      0.9999259668300376,
      0.999920678956203,
      0.999915391110332,
      0.9999101032924247,
      0.9999048155024809,
      0.9998995277405003,
      0.9998942400064829,
      0.9998889523004285,
      0.999883664622337,
      0.9998783769722083,
      0.999873089350042,
      0.9998678017558382,
      0.9998625141895967,
      0.9998572266513174,
      0.9998519391409999,
      0.9998466516586444,
      0.9998413642042505,
      0.9998360767778182,
      0.9998307893793471,
      0.9998255020088375,
      0.9998202146662888,
      0.9998149273517011,
      0.9998096400650742,
      0.999804352806408,
      0.9997990655757022,
      0.9997937783729568,
      0.9997884911981716,
      0.9997832040513465
    },
    {
      1.0,
      0.9999973558603371,
      0.9999947117276657,
      0.9999920676019859,
      0.9999894234832974,
      0.9999867793716004,
      0.9999841352668948,
      0.9999814911691806,
      0.9999788470784576,
      0.9999762029947261,
      0.999973558917986,
      0.9999709148482371,
      0.9999682707854796,
      0.9999656267297133,
      0.9999629826809383,
      0.9999603386391546,
      0.999957694604362,
      0.9999550505765606,
      0.9999524065557505,
      0.9999497625419316,
      0.9999471185351038,
      0.999944474535267,
      0.9999418305424215,
      0.999939186556567,
      0.9999365425777036,
      0.9999338986058312,
      0.9999312546409499,
      0.9999286106830596,
      0.9999259667321603,
      0.999923322788252,
      0.9999206788513347,
      0.9999180349214083,
      0.9999153909984728,
      0.9999127470825283,
      0.9999101031735746,
      0.9999074592716118,
      0.99990481537664,
      0.9999021714886589,
      0.9998995276076686,
      0.9998968837336691,
      0.9998942398666605
    },
    {
      1.0,
      0.9999986779284207,
      0.9999973558585893,
      0.9999960337905057,
      0.99999471172417,
      0.9999933896595822,
      0.9999920675967423,
      0.9999907455356501,
      0.9999894234763059,
      0.9999881014187095,
      0.9999867793628611,
      0.9999854573087604,
      0.9999841352564076,
      0.9999828132058026,
      0.9999814911569455,
      0.9999801691098362,
      0.9999788470644748,
      0.9999775250208612,
      0.9999762029789955,
      0.9999748809388775,
      0.9999735589005074,
      0.9999722368638853,
      0.9999709148290108,
      0.9999695927958842,
      0.9999682707645055,
      0.9999669487348745,
      0.9999656267069913,
      0.999964304680856,
      0.9999629826564685,
      0.9999616606338289,
      0.9999603386129369,
      0.9999590165937928,
      0.9999576945763966,
      0.9999563725607481,
      0.9999550505468474,
      0.9999537285346946,
      0.9999524065242895,
      0.9999510845156322,
      0.9999497625087227,
      0.999948440503561
    },
    {
      1.0,
      0.9999993389637734,
      0.9999986779279837,
      0.999998016892631,
      0.9999973558577153,
      0.9999966948232366,
      0.9999960337891948,
      0.99999537275559,
      0.9999947117224222,
      0.9999940506896913,
      0.9999933896573974,
      0.9999927286255405,
      0.9999920675941204,
      0.9999914065631375,
      0.9999907455325914,
      0.9999900845024823,
      0.9999894234728102,
      0.999988762443575,
      0.9999881014147769,
      0.9999874403864156,
      0.9999867793584913,
      0.999986118331004,
      0.9999854573039537,
      0.9999847962773404,
      0.999984135251164,
      0.9999834742254246,
      0.9999828132001221,
      0.9999821521752565,
      0.999981491150828,
      0.9999808301268364,
      0.9999801691032818,
      0.9999795080801641,
      0.9999788470574834,
      0.9999781860352396,
      0.9999775250134328,
      0.999976863992063,
      0.9999762029711301,
      0.9999755419506342,
      0.9999748809305752
    },
    {
      1.0,
      0.9999996694817774,
      0.9999993389636641,
      0.99999900844566,
      0.9999986779277652,
      0.9999983474099796,
      0.9999980168923033,
      0.9999976863747362,
      0.9999973558572783,
      0.9999970253399297,
      0.9999966948226904,
      0.9999963643055603,
      0.9999960337885394,
      0.9999957032716277,
      0.9999953727548253,
      0.9999950422381322,
      0.9999947117215482,
      0.9999943812050736,
      0.9999940506887081,
      0.9999937201724519,
      0.9999933896563049,
      0.9999930591402673,
      0.9999927286243387,
      0.9999923981085196,
      0.9999920675928096,
      0.9999917370772088,
      0.9999914065617173,
      0.9999910760463351,
      0.999990745531062,
      0.9999904150158982,
      0.9999900845008437,
      0.9999897539858984,
      0.9999894234710623,
      0.9999890929563355,
      0.999988762441718,
      0.9999884319272097,
      0.9999881014128105,
      0.9999877708985206
    },
    {
      1.0,
      0.9999998347408614,
      0.9999996694817501,
      0.9999995042226661,
      0.9999993389636095,
      0.9999991737045801,
      0.9999990084455781,
      0.9999988431866034,
      0.999998677927656,
      0.9999985126687359,
      0.9999983474098431,
      0.9999981821509776,
      0.9999980168921394,
      0.9999978516333285,
      0.999997686374545,
      0.9999975211157888,
      0.9999973558570598,
      0.9999971905983582,
      0.9999970253396839,
      0.9999968600810369,
      0.9999966948224173,
      0.9999965295638249,
      0.9999963643052598,
      0.9999961990467221,
      0.9999960337882117,
      0.9999958685297285,
      0.9999957032712726,
      0.9999955380128441,
      0.999995372754443,
      0.9999952074960691,
      0.9999950422377225,
      0.9999948769794033,
      0.9999947117211112,
      0.9999945464628466,
      0.9999943812046093,
      0.9999942159463993,
      0.9999940506882166
    },
    {
      1.0,
      0.9999999173704239,
      0.9999998347408546,
      0.9999997521112921,
      0.9999996694817365,
      0.9999995868521877,
      0.9999995042226457,
      0.9999994215931105,
      0.9999993389635822,
      0.9999992563340607,
      0.999999173704546,
      0.9999990910750381,
      0.9999990084455371,
      0.999998925816043,
      0.9999988431865556,
      0.999998760557075,
      0.9999986779276013,
      0.9999985952981345,
      0.9999985126686745,
      0.9999984300392212,
      0.9999983474097748,
      0.9999982647803353,
      0.9999981821509025,
      0.9999980995214766,
      0.9999980168920575,
      0.9999979342626453,
      0.9999978516332398,
      0.9999977690038412,
      0.9999976863744494,
      0.9999976037450645,
      0.9999975211156864,
      0.9999974384863151,
      0.9999973558569506,
      0.999997273227593,
      0.9999971905982422,
      0.9999971079688982
    },
    {
      1.0,
      0.9999999586852102,
      0.9999999173704222,
      0.9999998760556358,
      0.9999998347408512,
      0.9999997934260683,
      0.999999752111287,
      0.9999997107965075,
      0.9999996694817297,
      0.9999996281669535,
      0.9999995868521792,
      0.9999995455374064,
      0.9999995042226354,
      0.9999994629078661,
      0.9999994215930986,
      0.9999993802783327,
      0.9999993389635685,
      0.9999992976488061,
      0.9999992563340453,
      0.9999992150192863,
      0.999999173704529,
      0.9999991323897733,
      0.9999990910750194,
      0.9999990497602672,
      0.9999990084455167,
      0.9999989671307679,
      0.9999989258160208,
      0.9999988845012754,
      0.9999988431865318,
      0.9999988018717898,
      0.9999987605570495,
      0.9999987192423109,
      0.999998677927574,
      0.9999986366128389,
      0.9999985952981055
    },
    {
      1.0,
      0.9999999793426047,
      0.9999999586852099,
      0.9999999380278154,
      0.9999999173704213,
      0.9999998967130277,
      0.9999998760556346,
      0.9999998553982418,
      0.9999998347408494,
      0.9999998140834576,
      0.9999997934260662,
      0.999999772768675,
      0.9999997521112844,
      0.9999997314538942,
      0.9999997107965045,
      0.9999996901391152,
      0.9999996694817262,
      0.9999996488243378,
      0.9999996281669497,
      0.9999996075095621,
      0.9999995868521748,
      0.9999995661947881,
      0.9999995455374018,
      0.9999995248800159,
      0.9999995042226303,
      0.9999994835652453,
      0.9999994629078606,
      0.9999994422504764,
      0.9999994215930926,
      0.9999994009357093,
      0.9999993802783264,
      0.9999993596209438,
      0.9999993389635617,
      0.99999931830618
    },
    {
      1.0,
      0.9999999896713022,
      0.9999999793426045,
      0.9999999690139071,
      0.9999999586852096,
      0.9999999483565123,
      0.9999999380278151,
      0.9999999276991179,
      0.9999999173704209,
      0.999999907041724,
      0.9999998967130271,
      0.9999998863843305,
      0.9999998760556339,
      0.9999998657269374,
      0.999999855398241,
      0.9999998450695448,
      0.9999998347408486,
      0.9999998244121525,
      0.9999998140834566,
      0.9999998037547607,
      0.999999793426065,
      0.9999997830973694,
      0.9999997727686739,
      0.9999997624399785,
      0.9999997521112831,
      0.999999741782588,
      0.9999997314538929,
      0.9999997211251979,
      0.999999710796503,
      0.9999997004678082,
      0.9999996901391135,
      0.999999679810419,
      0.9999996694817246
    },
    {
      1.0,
      0.999999994835651,
      0.9999999896713022,
      0.9999999845069534,
      0.9999999793426045,
      0.9999999741782557,
      0.999999969013907,
      0.9999999638495582,
      0.9999999586852095,
      0.9999999535208608,
      0.9999999483565121,
      0.9999999431921635,
      0.9999999380278148,
      0.9999999328634663,
      0.9999999276991177,
      0.9999999225347692,
      0.9999999173704207,
      0.9999999122060722,
      0.9999999070417237,
      0.9999999018773753,
      0.9999998967130269,
      0.9999998915486785,
      0.9999998863843302,
      0.9999998812199818,
      0.9999998760556336,
      0.9999998708912853,
      0.9999998657269371,
      0.9999998605625888,
      0.9999998553982407,
      0.9999998502338925,
      0.9999998450695444,
      0.9999998399051963
    },
    {
      1.0,
      0.9999999974178255,
      0.999999994835651,
      0.9999999922534767,
      0.9999999896713022,
      0.9999999870891277,
      0.9999999845069534,
      0.9999999819247789,
      0.9999999793426045,
      0.9999999767604301,
      0.9999999741782557,
      0.9999999715960813,
      0.9999999690139069,
      0.9999999664317325,
      0.9999999638495581,
      0.9999999612673838,
      0.9999999586852094,
      0.999999956103035,
      0.9999999535208607,
      0.9999999509386864,
      0.9999999483565121,
      0.9999999457743377,
      0.9999999431921635,
      0.9999999406099891,
      0.9999999380278148,
      0.9999999354456405,
      0.9999999328634662,
      0.9999999302812919,
      0.9999999276991176,
      0.9999999251169434,
      0.9999999225347691
    },
    {
      1.0,
      0.9999999987089128,
      0.9999999974178255,
      0.9999999961267383,
      0.999999994835651,
      0.9999999935445638,
      0.9999999922534767,
      0.9999999909623895,
      0.9999999896713022,
      0.999999988380215,
      0.9999999870891277,
      0.9999999857980405,
      0.9999999845069534,
      0.9999999832158661,
      0.9999999819247789,
      0.9999999806336917,
      0.9999999793426045,
      0.9999999780515173,
      0.9999999767604301,
      0.9999999754693428,
      0.9999999741782557,
      0.9999999728871685,
      0.9999999715960813,
      0.9999999703049941,
      0.9999999690139069,
      0.9999999677228197,
      0.9999999664317325,
      0.9999999651406454,
      0.9999999638495581,
      0.9999999625584709
    },
    {
      1.0,
      0.9999999993544564,
      0.9999999987089128,
      0.9999999980633691,
      0.9999999974178255,
      0.9999999967722819,
      0.9999999961267383,
      0.9999999954811947,
      0.999999994835651,
      0.9999999941901074,
      0.9999999935445638,
      0.9999999928990202,
      0.9999999922534766,
      0.9999999916079331,
      0.9999999909623895,
      0.9999999903168458,
      0.9999999896713022,
      0.9999999890257586,
      0.999999988380215,
      0.9999999877346714,
      0.9999999870891277,
      0.9999999864435841,
      0.9999999857980405,
      0.9999999851524969,
      0.9999999845069534,
      0.9999999838614098,
      0.9999999832158661,
      0.9999999825703225,
      0.9999999819247789
    },
    {
      1.0,
      0.9999999996772282,
      0.9999999993544564,
      0.9999999990316846,
      0.9999999987089128,
      0.999999998386141,
      0.9999999980633691,
      0.9999999977405973,
      0.9999999974178255,
      0.9999999970950537,
      0.9999999967722819,
      0.9999999964495101,
      0.9999999961267383,
      0.9999999958039665,
      0.9999999954811947,
      0.9999999951584229,
      0.999999994835651,
      0.9999999945128792,
      0.9999999941901074,
      0.9999999938673356,
      0.9999999935445638,
      0.999999993221792,
      0.9999999928990202,
      0.9999999925762484,
      0.9999999922534766,
      0.9999999919307049,
      0.9999999916079331,
      0.9999999912851613
    },
    {
      1.0,
      0.9999999998386141,
      0.9999999996772282,
      0.9999999995158423,
      0.9999999993544564,
      0.9999999991930705,
      0.9999999990316846,
      0.9999999988702987,
      0.9999999987089128,
      0.9999999985475269,
      0.999999998386141,
      0.999999998224755,
      0.9999999980633691,
      0.9999999979019832,
      0.9999999977405973,
      0.9999999975792114,
      0.9999999974178255,
      0.9999999972564396,
      0.9999999970950537,
      0.9999999969336678,
      0.9999999967722819,
      0.999999996610896,
      0.9999999964495101,
      0.9999999962881242,
      0.9999999961267383,
      0.9999999959653524,
      0.9999999958039665
    },
    {
      1.0,
      0.9999999999193071,
      0.9999999998386141,
      0.9999999997579212,
      0.9999999996772282,
      0.9999999995965353,
      0.9999999995158423,
      0.9999999994351494,
      0.9999999993544564,
      0.9999999992737635,
      0.9999999991930705,
      0.9999999991123776,
      0.9999999990316846,
      0.9999999989509917,
      0.9999999988702987,
      0.9999999987896058,
      0.9999999987089128,
      0.9999999986282199,
      0.9999999985475269,
      0.999999998466834,
      0.999999998386141,
      0.9999999983054481,
      0.999999998224755,
      0.9999999981440622,
      0.9999999980633691,
      0.9999999979826762
    },
    {
      1.0,
      0.9999999999596535,
      0.9999999999193071,
      0.9999999998789606,
      0.9999999998386141,
      0.9999999997982676,
      0.9999999997579212,
      0.9999999997175747,
      0.9999999996772282,
      0.9999999996368817,
      0.9999999995965353,
      0.9999999995561888,
      0.9999999995158423,
      0.9999999994754958,
      0.9999999994351494,
      0.9999999993948029,
      0.9999999993544564,
      0.9999999993141099,
      0.9999999992737635,
      0.999999999233417,
      0.9999999991930705,
      0.999999999152724,
      0.9999999991123776,
      0.9999999990720311,
      0.9999999990316846
    },
    {
      1.0,
      0.9999999999798268,
      0.9999999999596535,
      0.9999999999394803,
      0.9999999999193071,
      0.9999999998991338,
      0.9999999998789606,
      0.9999999998587873,
      0.9999999998386141,
      0.9999999998184409,
      0.9999999997982676,
      0.9999999997780944,
      0.9999999997579212,
      0.9999999997377479,
      0.9999999997175747,
      0.9999999996974014,
      0.9999999996772282,
      0.999999999657055,
      0.9999999996368817,
      0.9999999996167085,
      0.9999999995965353,
      0.999999999576362,
      0.9999999995561888,
      0.9999999995360155
    },
    {
      1.0,
      0.9999999999899134,
      0.9999999999798268,
      0.9999999999697401,
      0.9999999999596535,
      0.9999999999495669,
      0.9999999999394803,
      0.9999999999293937,
      0.9999999999193071,
      0.9999999999092204,
      0.9999999998991338,
      0.9999999998890472,
      0.9999999998789606,
      0.999999999868874,
      0.9999999998587873,
      0.9999999998487007,
      0.9999999998386141,
      0.9999999998285275,
      0.9999999998184409,
      0.9999999998083542,
      0.9999999997982676,
      0.999999999788181,
      0.9999999997780944
    },
    {
      1.0,
      0.9999999999949567,
      0.9999999999899134,
      0.9999999999848701,
      0.9999999999798268,
      0.9999999999747835,
      0.9999999999697401,
      0.9999999999646968,
      0.9999999999596535,
      0.9999999999546102,
      0.9999999999495669,
      0.9999999999445236,
      0.9999999999394803,
      0.999999999934437,
      0.9999999999293937,
      0.9999999999243504,
      0.9999999999193071,
      0.9999999999142637,
      0.9999999999092204,
      0.9999999999041771,
      0.9999999998991338,
      0.9999999998940905
    },
    {
      1.0,
      0.9999999999974784,
      0.9999999999949567,
      0.999999999992435,
      0.9999999999899134,
      0.9999999999873918,
      0.9999999999848701,
      0.9999999999823485,
      0.9999999999798268,
      0.9999999999773052,
      0.9999999999747835,
      0.9999999999722617,
      0.9999999999697401,
      0.9999999999672184,
      0.9999999999646968,
      0.9999999999621751,
      0.9999999999596535,
      0.9999999999571318,
      0.9999999999546102,
      0.9999999999520885,
      0.9999999999495669
    },
    {
      1.0,
      0.9999999999987391,
      0.9999999999974784,
      0.9999999999962175,
      0.9999999999949567,
      0.9999999999936958,
      0.999999999992435,
      0.9999999999911742,
      0.9999999999899134,
      0.9999999999886525,
      0.9999999999873918,
      0.9999999999861309,
      0.9999999999848701,
      0.9999999999836092,
      0.9999999999823485,
      0.9999999999810876,
      0.9999999999798268,
      0.9999999999785659,
      0.9999999999773052,
      0.9999999999760443
    },
    {
      1.0,
      0.9999999999993696,
      0.9999999999987391,
      0.9999999999981087,
      0.9999999999974784,
      0.999999999996848,
      0.9999999999962175,
      0.9999999999955871,
      0.9999999999949567,
      0.9999999999943263,
      0.9999999999936958,
      0.9999999999930654,
      0.999999999992435,
      0.9999999999918047,
      0.9999999999911742,
      0.9999999999905438,
      0.9999999999899134,
      0.999999999989283,
      0.9999999999886525
    },
    {
      1.0,
      0.9999999999996848,
      0.9999999999993696,
      0.9999999999990544,
      0.9999999999987391,
      0.9999999999984239,
      0.9999999999981087,
      0.9999999999977935,
      0.9999999999974784,
      0.9999999999971632,
      0.999999999996848,
      0.9999999999965328,
      0.9999999999962175,
      0.9999999999959023,
      0.9999999999955871,
      0.9999999999952719,
      0.9999999999949567,
      0.9999999999946415
    },
    {
      1.0,
      0.9999999999998423,
      0.9999999999996848,
      0.9999999999995272,
      0.9999999999993696,
      0.999999999999212,
      0.9999999999990544,
      0.9999999999988968,
      0.9999999999987391,
      0.9999999999985816,
      0.9999999999984239,
      0.9999999999982664,
      0.9999999999981087,
      0.9999999999979512,
      0.9999999999977935,
      0.999999999997636,
      0.9999999999974784
    },
    {
      1.0,
      0.9999999999999212,
      0.9999999999998423,
      0.9999999999997636,
      0.9999999999996848,
      0.999999999999606,
      0.9999999999995272,
      0.9999999999994484,
      0.9999999999993696,
      0.9999999999992908,
      0.999999999999212,
      0.9999999999991331,
      0.9999999999990544,
      0.9999999999989756,
      0.9999999999988968,
      0.999999999998818
    },
    {
      1.0,
      0.9999999999999606,
      0.9999999999999212,
      0.9999999999998818,
      0.9999999999998423,
      0.999999999999803,
      0.9999999999997636,
      0.9999999999997242,
      0.9999999999996848,
      0.9999999999996454,
      0.999999999999606,
      0.9999999999995666,
      0.9999999999995272,
      0.9999999999994877,
      0.9999999999994484
    },
    {
      1.0,
      0.9999999999999803,
      0.9999999999999606,
      0.9999999999999409,
      0.9999999999999212,
      0.9999999999999015,
      0.9999999999998818,
      0.9999999999998621,
      0.9999999999998423,
      0.9999999999998227,
      0.999999999999803,
      0.9999999999997833,
      0.9999999999997636,
      0.9999999999997439
    },
    {
      1.0,
      0.9999999999999901,
      0.9999999999999803,
      0.9999999999999705,
      0.9999999999999606,
      0.9999999999999507,
      0.9999999999999409,
      0.999999999999931,
      0.9999999999999212,
      0.9999999999999113,
      0.9999999999999015,
      0.9999999999998916,
      0.9999999999998818
    },
    {
      1.0,
      0.9999999999999951,
      0.9999999999999901,
      0.9999999999999852,
      0.9999999999999803,
      0.9999999999999754,
      0.9999999999999705,
      0.9999999999999655,
      0.9999999999999606,
      0.9999999999999557,
      0.9999999999999507,
      0.9999999999999458
    },
    {
      1.0,
      0.9999999999999976,
      0.9999999999999951,
      0.9999999999999926,
      0.9999999999999901,
      0.9999999999999877,
      0.9999999999999852,
      0.9999999999999828,
      0.9999999999999803,
      0.9999999999999778,
      0.9999999999999754
    },
    {
      1.0,
      0.9999999999999988,
      0.9999999999999976,
      0.9999999999999963,
      0.9999999999999951,
      0.9999999999999939,
      0.9999999999999926,
      0.9999999999999913,
      0.9999999999999901,
      0.9999999999999889
    },
    {
      1.0,
      0.9999999999999993,
      0.9999999999999988,
      0.9999999999999981,
      0.9999999999999976,
      0.9999999999999969,
      0.9999999999999963,
      0.9999999999999957,
      0.9999999999999951
    },
    {
      1.0,
      0.9999999999999997,
      0.9999999999999993,
      0.9999999999999991,
      0.9999999999999988,
      0.9999999999999984,
      0.9999999999999981,
      0.9999999999999979
    },
    {
      1.0,
      0.9999999999999999,
      0.9999999999999997,
      0.9999999999999996,
      0.9999999999999993,
      0.9999999999999992,
      0.9999999999999991
    },
    {
      1.0,
      0.9999999999999999,
      0.9999999999999999,
      0.9999999999999998,
      0.9999999999999997,
      0.9999999999999997
    },
    {1.0, 1.0, 0.9999999999999999, 0.9999999999999999, 0.9999999999999999},
    {1.0, 1.0, 1.0, 0.9999999999999999},
    {1.0, 1.0, 1.0},
    {1.0, 1.0},
    {1.0}
  };
}
