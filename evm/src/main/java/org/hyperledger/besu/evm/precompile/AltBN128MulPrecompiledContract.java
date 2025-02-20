/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evm.precompile;

import org.hyperledger.besu.crypto.altbn128.AltBn128Point;
import org.hyperledger.besu.crypto.altbn128.Fq;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings;

import java.math.BigInteger;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;

public class AltBN128MulPrecompiledContract extends AbstractAltBnPrecompiledContract {

  private static final BigInteger MAX_N =
      new BigInteger(
          "115792089237316195423570985008687907853269984665640564039457584007913129639935");

  private final Gas gasCost;

  private AltBN128MulPrecompiledContract(final GasCalculator gasCalculator, final Gas gasCost) {
    super("AltBN128Mul", gasCalculator, LibEthPairings.EIP196_MUL_OPERATION_RAW_VALUE);
    this.gasCost = gasCost;
  }

  public static AltBN128MulPrecompiledContract byzantium(final GasCalculator gasCalculator) {
    return new AltBN128MulPrecompiledContract(gasCalculator, Gas.of(40_000));
  }

  public static AltBN128MulPrecompiledContract istanbul(final GasCalculator gasCalculator) {
    return new AltBN128MulPrecompiledContract(gasCalculator, Gas.of(6_000));
  }

  @Override
  public Gas gasRequirement(final Bytes input) {
    return gasCost;
  }

  @Override
  public Bytes compute(final Bytes input, final MessageFrame messageFrame) {
    if (useNative) {
      return computeNative(input, messageFrame);
    } else {
      return computeDefault(input);
    }
  }

  private static Bytes computeDefault(final Bytes input) {
    final BigInteger x = extractParameter(input, 0, 32);
    final BigInteger y = extractParameter(input, 32, 32);
    final BigInteger n = extractParameter(input, 64, 32);

    final AltBn128Point p = new AltBn128Point(Fq.create(x), Fq.create(y));
    if (!p.isOnCurve() || n.compareTo(MAX_N) > 0) {
      return null;
    }
    final AltBn128Point product = p.multiply(n);

    final Bytes xResult = product.getX().toBytes();
    final Bytes yResult = product.getY().toBytes();
    final MutableBytes result = MutableBytes.create(64);
    xResult.copyTo(result, 32 - xResult.size());
    yResult.copyTo(result, 64 - yResult.size());

    return result;
  }

  private static BigInteger extractParameter(
      final Bytes input, final int offset, final int length) {
    if (offset > input.size() || length == 0) {
      return BigInteger.ZERO;
    }
    final byte[] raw = Arrays.copyOfRange(input.toArray(), offset, offset + length);
    return new BigInteger(1, raw);
  }
}
