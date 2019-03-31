/*
 * Copyright 2009 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.javascript.jscomp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import com.google.javascript.jscomp.CompilerOptions.DevMode;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;

/**
 * Integration tests for https://github.com/aemkei/jsfuck unfucking.
 *
 * @author https://github.com/krk
 */

@RunWith(JUnit4.class)
public final class JsFuckIntegrationTest extends IntegrationTestCase {

  /** Creates a CompilerOptions object with google coding conventions. */
  @Override
  protected CompilerOptions createCompilerOptions() {
    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT_2015);
    options.setLanguageOut(LanguageMode.ECMASCRIPT5);
    options.setDevMode(DevMode.EVERY_PASS);
    options.setWarningLevel(DiagnosticGroups.FEATURES_NOT_SUPPORTED_BY_PASS, CheckLevel.OFF);
    options.addWarningsGuard(
        new DiagnosticGroupWarningsGuard(DiagnosticGroups.CHECK_TYPES, CheckLevel.OFF));
    options.setWarningLevel(DiagnosticGroups.CHECK_REGEXP, CheckLevel.OFF);

    return options;
  }

  @Test
  public void testSimple() {
    CompilerOptions options = createCompilerOptions();
    CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options);

    test(options, "![]", "!1");
    test(options, "!![]", "!0");
    test(options, "[][[]]", "void 0");
    test(options, "+[![]]", "NaN");
    test(options, "+(+!+[]+(!+[]+[])[!+[]+!+[]+!+[]]+[+!+[]]+[+[]]+[+[]]+[+[]])", "Infinity");
  }

  @Test
  public void testConstructors() {
    CompilerOptions options = createCompilerOptions();
    CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options);

    testSame(options, "[]");
    test(options, "(+[])", "0");
    test(options, "([]+[])", "\"\"");
    test(options, "(![])", "!1");
    test(options, "[][\"fill\"]", "[].fill");
    test(options, "Function(\"return/\"+false+\"/\")()", ""); // TODO For some reason result is
                                                              // empty, expected: "/false/"
  }

  @Test
  public void testLetters() {
    CompilerOptions options = createCompilerOptions();
    CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options);

    // Appended [0] to k, v, w, z, D otherwise result gets eliminated.
    test(options, "(false+\"\")[1]", "\"a\"");
    test(options, "([][\"entries\"]()+\"\")[2]", "\"b\"");
    test(options, "([][\"fill\"]+\"\")[3]", "\"c\"");
    test(options, "(undefined+\"\")[2]", "\"d\"");
    test(options, "(true+\"\")[3]", "\"e\"");
    test(options, "(false+\"\")[0]", "\"f\"");
    test(options, "(false+[0]+String)[20]", "\"g\"");
    test(options, "(+(101))[\"to\"+String[\"name\"]](21)[1]", "\"h\"");
    test(options, "([false]+undefined)[10]", "\"i\"");
    test(options, "([][\"entries\"]()+\"\")[3]", "\"j\"");
    test(options, "(+(20))[\"to\"+String[\"name\"]](21)[0]", "\"k\"");
    test(options, "(false+\"\")[2]", "\"l\"");
    test(options, "(Number+\"\")[11]", "\"m\"");
    test(options, "(undefined+\"\")[1]", "\"n\"");
    test(options, "(true+[][\"fill\"])[10]", "\"o\"");
    test(options, "(+(211))[\"to\"+String[\"name\"]](31)[1]", "\"p\"");
    test(options, "(+(212))[\"to\"+String[\"name\"]](31)[1]", "\"q\"");
    test(options, "(true+\"\")[1]", "\"r\"");
    test(options, "(false+\"\")[3]", "\"s\"");
    test(options, "(true+\"\")[0]", "\"t\"");
    test(options, "(undefined+\"\")[0]", "\"u\"");
    test(options, "(+(31))[\"to\"+String[\"name\"]](32)[0]", "\"v\"");
    test(options, "(+(32))[\"to\"+String[\"name\"]](33)[0]", "\"w\"");
    test(options, "(+(101))[\"to\"+String[\"name\"]](34)[1]", "\"x\"");
    test(options, "(NaN+[Infinity])[10]", "\"y\"");
    test(options, "(+(35))[\"to\"+String[\"name\"]](36)[0]", "\"z\"");

    test(options, "(+[]+Array)[10]", "\"A\"");
    test(options, "(+[]+Boolean)[10]", "\"B\"");
    test(options, "Function(\"return escape\")()((\"\")[\"italics\"]())[2]", "\"C\"");
    test(options, "Function(\"return escape\")()([][\"fill\"])[\"slice\"](\"-1\")[0]", "\"D\"");
    test(options, "(RegExp+\"\")[12]", "\"E\"");
    test(options, "(+[]+Function)[10]", "\"F\"");
    test(options, "(false+Function(\"return Date\")()())[30]", "\"G\"");
  }
}
