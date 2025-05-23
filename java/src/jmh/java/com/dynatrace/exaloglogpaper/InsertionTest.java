//
// Copyright (c) 2024-2025 Dynatrace LLC. All rights reserved.
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

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

public class InsertionTest {

  @State(Scope.Thread)
  public static class TestState {

    @Param public SketchConfig sketchConfig;

    @Param({
      "1", "2", "5", "10", "20", "50", "100", "200", "500", "1000", "2000", "5000", "10000",
      "20000", "50000", "100000", "200000", "500000", "1000000"
    })
    public int numElements;

    @Param({"16"})
    public int dataSize;

    public byte[][] data;

    @Setup(Level.Trial)
    public void initTrial() {
      data = new byte[numElements][];
      IntStream.range(0, numElements)
          .parallel()
          .forEach(
              j -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                byte[] b = new byte[dataSize];
                random.nextBytes(b);
                data[j] = b;
              });
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void insert(TestState testState, Blackhole blackhole) {
    SketchConfig sketchConfig = testState.sketchConfig;
    Object sketch = sketchConfig.createEmptySketch();
    for (byte[] d : testState.data) {
      sketchConfig.add(sketch, d);
    }
    blackhole.consume(sketch);
  }
}
