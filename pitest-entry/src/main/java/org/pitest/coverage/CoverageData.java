/*
 * Copyright 2012 Henry Coles
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.pitest.coverage;

import org.pitest.classinfo.ClassInfo;
import org.pitest.classinfo.ClassName;
import org.pitest.classpath.CodeSource;
import org.pitest.functional.FCollection;
import org.pitest.testapi.Description;
import org.pitest.util.Log;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toCollection;

public class CoverageData implements CoverageDatabase {

  private static final Logger                                 LOG           = Log
      .getLogger();

  // We calculate block coverage, but everything currently runs on line
  // coverage. Ugly mess of maps below should go when
  // api changed to work via blocks
  private final Map<InstructionLocation, Set<TestInfo>>       instructionCoverage;
  private final Map<BlockLocation, Set<Integer>>              blocksToLines = new LinkedHashMap<>();
  private final Map<ClassName, Map<ClassLine, Set<TestInfo>>> lineCoverage  = new LinkedHashMap<>();
  private final Map<String, Collection<ClassInfo>>            classesForFile;

  private final CodeSource                                    code;

  private final LineMap                                       lm;

  private final List<Description>                             failingTestDescriptions = new ArrayList<>();

  public CoverageData(final CodeSource code, final LineMap lm) {
    this(code, lm, new LinkedHashMap<>());
  }


  public CoverageData(final CodeSource code, final LineMap lm, Map<InstructionLocation, Set<TestInfo>> instructionCoverage) {
    this.instructionCoverage = instructionCoverage;
    this.code = code;
    this.lm = lm;
    this.classesForFile = FCollection.bucket(this.code.getCode(),
        keyFromClassInfo());
  }

  @Override
  public Collection<TestInfo> getTestsForInstructionLocation(InstructionLocation location) {
    return this.instructionCoverage.getOrDefault(location, Collections.emptySet());
  }

  @Override
  public Collection<TestInfo> getTestsForClassLine(final ClassLine classLine) {
    final Collection<TestInfo> result = getLineCoverageForClassName(
        classLine.getClassName()).get(classLine);
    if (result == null) {
      return Collections.emptyList();
    } else {
      return result;
    }
  }

  public boolean allTestsGreen() {
    return this.failingTestDescriptions.isEmpty();
  }

  public int getCountFailedTests() {
    return this.failingTestDescriptions.size();
  }

  public List<Description> getFailingTestDescriptions() {
    return failingTestDescriptions;
  }

  @Override
  public Collection<ClassInfo> getClassInfo(final Collection<ClassName> classes) {
    return this.code.getClassInfo(classes);
  }

  @Override
  public int getNumberOfCoveredLines(final Collection<ClassName> mutatedClass) {
    return mutatedClass.stream()
        .map(this::getLineCoverageForClassName)
        .mapToInt(Map::size)
        .sum();
  }

  @Override
  public Collection<TestInfo> getTestsForClass(final ClassName clazz) {
    return this.instructionCoverage.entrySet().stream()
            .filter(isFor(clazz))
            .flatMap(toTests())
            .collect(toCollection(() -> new TreeSet<>(new TestInfoNameComparator())));
  }

  public void calculateClassCoverage(final CoverageResult cr) {

    checkForFailedTest(cr);
    final TestInfo ti = this.createTestInfo(cr.getTestUnitDescription(),
        cr.getExecutionTime(), cr.getNumberOfCoveredBlocks());
    for (final BlockLocation each : cr.getCoverage()) {
      for (int i = each.getFirstInsnInBlock();
           i <= each.getLastInsnInBlock(); i++) {
        addTestsToBlockMap(ti, new InstructionLocation(each, i));
      }
    }
  }

  private void addTestsToBlockMap(final TestInfo ti, InstructionLocation each) {
    Set<TestInfo> tests = this.instructionCoverage.get(each);
    if (tests == null) {
      tests = new TreeSet<>(new TestInfoNameComparator());
      this.instructionCoverage.put(each, tests);
    }
    tests.add(ti);
  }

  @Override
  public BigInteger getCoverageIdForClass(final ClassName clazz) {
    final Collection<TestInfo> coverage = getTestsForClass(clazz);
    if (coverage.isEmpty()) {
      return BigInteger.ZERO;
    }

    return generateCoverageNumber(coverage);
  }

  public List<BlockCoverage> createCoverage() {
    return FCollection.map(this.instructionCoverage.entrySet(), toBlockCoverage());
  }

  private static Function<Entry<InstructionLocation, Set<TestInfo>>, BlockCoverage> toBlockCoverage() {
    return a -> new BlockCoverage(a.getKey().getBlockLocation(), FCollection.map(a.getValue(),
        TestInfo.toName()));
  }

  @Override
  public Collection<ClassInfo> getClassesForFile(final String sourceFile,
      String packageName) {
    final Collection<ClassInfo> value = classesForFile.get(
        keyFromSourceAndPackage(sourceFile, packageName));
    if (value == null) {
      return Collections.emptyList();
    } else {
      return value;
    }
  }

  @Override
  public CoverageSummary createSummary() {
    return new CoverageSummary(numberOfLines(), coveredLines());
  }

  private BigInteger generateCoverageNumber(Collection<TestInfo> coverage) {
    BigInteger coverageNumber = BigInteger.ZERO;
    Set<ClassName> testClasses = coverage.stream()
            .map(TestInfo.toDefiningClassName())
            .collect(Collectors.toSet());

    for (final ClassInfo each : this.code.getClassInfo(testClasses)) {
      coverageNumber = coverageNumber.add(each.getDeepHash());
    }

    return coverageNumber;
  }

  private static Function<ClassInfo, String> keyFromClassInfo() {

    return c -> keyFromSourceAndPackage(c.getSourceFileName(), c.getName()
        .getPackage().asJavaName());
  }

  private static String keyFromSourceAndPackage(final String sourceFile,
      final String packageName) {

    return packageName + " " + sourceFile;
  }

  private Collection<ClassName> allClasses() {
    return this.code.getCodeUnderTestNames();
  }

  private int numberOfLines() {
    return FCollection.fold(numberLines(), 0,
        this.code.getClassInfo(allClasses()));
  }

  private int coveredLines() {
    return getNumberOfCoveredLines(allClasses());
  }

  private BiFunction<Integer, ClassInfo, Integer> numberLines() {
    return (a, clazz) -> a + clazz.getNumberOfCodeLines();
  }

  private void checkForFailedTest(final CoverageResult cr) {
    if (!cr.isGreenTest()) {
      recordTestFailure(cr.getTestUnitDescription());
      LOG.severe(cr.getTestUnitDescription()
          + " did not pass without mutation.");
    }
  }

  private TestInfo createTestInfo(final Description description,
      final int executionTime, final int linesCovered) {
    final Optional<ClassName> testee = this.code.findTestee(description
        .getFirstTestClass());
    return new TestInfo(description.getFirstTestClass(),
        description.getQualifiedName(), executionTime, testee, linesCovered);
  }

  private Map<ClassLine, Set<TestInfo>> getLineCoverageForClassName(final ClassName clazz) {
    // Use any test that provided some coverage of the class
    // This fails to consider tests that only accessed a static variable
    // of the class in question as this does not register as coverage.
    final Map<ClassLine, Set<TestInfo>> map = this.lineCoverage.get(clazz);
    if (map != null) {
      return map;
    }

    return convertInstructionCoverageToLineCoverageForClass(clazz);

  }

  private Map<ClassLine, Set<TestInfo>> convertInstructionCoverageToLineCoverageForClass(
      ClassName clazz) {
    final List<Entry<InstructionLocation, Set<TestInfo>>> tests = FCollection.filter(
        this.instructionCoverage.entrySet(), isFor(clazz));

    final Map<ClassLine, Set<TestInfo>> linesToTests = new LinkedHashMap<>(
        0);

    for (final Entry<InstructionLocation, Set<TestInfo>> each : tests) {
      for (final int line : getLinesForBlock(each.getKey().getBlockLocation())) {
        final Set<TestInfo> tis = getLineTestSet(clazz, linesToTests, each, line);
        tis.addAll(each.getValue());
      }
    }

    this.lineCoverage.put(clazz, linesToTests);
    return linesToTests;
  }

  private static Set<TestInfo> getLineTestSet(ClassName clazz,
      Map<ClassLine, Set<TestInfo>> linesToTests,
      Entry<InstructionLocation, Set<TestInfo>> each, int line) {
    final ClassLine cl = new ClassLine(clazz, line);
    Set<TestInfo> tis = linesToTests.get(cl);
    if (tis == null) {
      tis = new TreeSet<>(new TestInfoNameComparator());
      tis.addAll(each.getValue());
      linesToTests.put(new ClassLine(clazz, line), tis);
    }
    return tis;
  }

  private Set<Integer> getLinesForBlock(BlockLocation bl) {
    Set<Integer> lines = this.blocksToLines.get(bl);
    if (lines == null) {
      calculateLinesForBlocks(bl.getLocation().getClassName());
      lines = this.blocksToLines.get(bl);
      if (lines == null) {
        lines = Collections.emptySet();
      }
    }

    return lines;
  }

  private void calculateLinesForBlocks(ClassName className) {
    final Map<BlockLocation, Set<Integer>> lines = this.lm.mapLines(className);
    this.blocksToLines.putAll(lines);
  }

  private void recordTestFailure(final Description testDescription) {
    this.failingTestDescriptions.add(testDescription);
  }

  private Function<Entry<InstructionLocation, Set<TestInfo>>, Stream<TestInfo>> toTests() {
    return a -> a.getValue().stream();
  }

  private Predicate<Entry<InstructionLocation, Set<TestInfo>>> isFor(ClassName clazz) {
    return a -> a.getKey().isFor(clazz);
  }

}
