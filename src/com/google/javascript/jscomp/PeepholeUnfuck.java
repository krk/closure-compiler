/*
 * Copyright 2019 The Closure Compiler Authors.
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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.TokenStream;

class PeepholeUnfuck extends AbstractPeepholeOptimization {

  private static class Tuple<X, Y> {
    public final X x;
    public final Y y;

    public Tuple(X x, Y y) {
      this.x = x;
      this.y = y;
    }
  }

  private static final SimpleDateFormat jsDateFormat =
      new SimpleDateFormat("EEE MMM dd yyyy '00:00:00' 'GMT'Z '('z')'");

  // Only constant characters shall be referenced, e.g. "G".
  private static final String dateNow =
      "Mon Jan 01 2001 00:00:00 GMT+0100 (Central European Standard Time)";
  private static final DecimalFormat doubleIntFormat = new DecimalFormat("#.##############");

  private static final Set<String> arrayFunctions = new HashSet<String>(
      Arrays.asList("concat", "copyWithin", "entries", "every", "fill", "filter", "find",
          "findIndex", "flat", "flatMap", "forEach", "includes", "indexOf", "join", "keys",
          "lastIndexOf", "map", "pop", "push", "reduce", "reduceRight", "reverse", "shift", "slice",
          "some", "sort", "splice", "toLocaleString", "toSource", "toString", "unshift", "values"));

  private static final Set<String> evalArrayFunctions =
      new HashSet<String>(Arrays.asList("fill", "filter", "sort"));

  private static final Set<String> constructors = new HashSet<String>(
      Arrays.asList("Array", "Number", "String", "Boolean", "Function", "RegExp"));

  // Only applies to empty strings, as in `"".blink()`.
  private static final Map<String, Tuple<String, String>> stringToHtml =
      new HashMap<String, Tuple<String, String>>() {
        {
          put("anchor", new Tuple<String, String>("<a name=\"", "\"></a>"));
          put("big", new Tuple<String, String>("<big>", "</big>"));
          put("blink", new Tuple<String, String>("<blink>", "</blink>"));
          put("bold", new Tuple<String, String>("<b>", "</b>"));
          put("fixed", new Tuple<String, String>("<tt>", "</tt>"));
          put("fontcolor", new Tuple<String, String>("<font color=\"", "\"></font>"));
          put("fontsize", new Tuple<String, String>("<font size=\"", "\"></font>"));
          put("italics", new Tuple<String, String>("<i>", "</i>"));
          put("link", new Tuple<String, String>("<a href=\"", "\"></a>"));
          put("small", new Tuple<String, String>("<small>", "</small>"));
          put("strike", new Tuple<String, String>("<strike>", "</strike>"));
          put("sub", new Tuple<String, String>("<sub>", "</sub>"));
          put("sup", new Tuple<String, String>("<sup>", "</sup>"));
        }
      };

  PeepholeUnfuck() {}

  /**
   * Tries to apply our various peephole unfuckifications on the passed in node.
   */
  @Override
  public Node optimizeSubtree(Node n) {
    Node node = tryUndefined(n);
    if (node != n) {
      return node;
    }

    node = tryStringIndexedString(n);
    if (node != n) {
      return node;
    }

    node = tryArrayLiteralFunctionStringCoercion(n);
    if (node != n) {
      return node;
    }

    node = tryArrayFunctionConstructorInvocation(n);
    if (node != n) {
      return node;
    }

    node = tryFunctionConstructorInvocation(n);
    if (node != n) {
      return node;
    }

    node = tryUnfuckArrayEntries(n);
    if (node != n) {
      return node;
    }

    node = tryConstructorCoercion(n);
    if (node != n) {
      return node;
    }

    node = tryConstructorNameCoercion(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateIntToStringBase(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateEscapes(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateStringToHtmlElement(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateStringSlice(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateDate(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateNewDate(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateGetConstructor(n);
    if (node != n) {
      return node;
    }

    node = tryEvaluateEval(n);
    if (node != n) {
      return node;
    }

    node = tryCoerceNaNObjectLit(n);
    if (node != n) {
      return node;
    }

    return n;
  }

  private Node tryEvaluateEval(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "eval") {
      return n;
    }

    Node str = n.getLastChild();
    if (!str.isString()) {
      return n;
    }

    Node replacement = tryEval(str.getString(), str, n);
    if (replacement == n) {
      return n;
    }

    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateGetConstructor(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }
    Node left = n.getFirstChild();
    Node right = n.getLastChild();
    if (!isGetPropOrElem(left) && !isGetPropOrElem(right)) {
      return n;
    }

    Node newLeft = null;
    if (isGetPropOrElem(left) && left.hasTwoChildren()) {
      Node empty = left.getFirstChild();
      Node ctor = left.getLastChild();
      if (empty.isString() && empty.getString() == "" && ctor.isString()
          && ctor.getString() == "constructor") {
        newLeft = IR.name("String");
      }
    }

    Node newRight = null;
    if (isGetPropOrElem(right) && right.hasTwoChildren()) {
      Node empty = right.getFirstChild();
      Node ctor = right.getLastChild();
      if (empty.isString() && empty.getString() == "" && ctor.isString()
          && ctor.getString() == "constructor") {
        newRight = IR.name("String");
      }
    }

    if (newLeft == null && newRight == null) {
      return n;
    }
    if (newLeft == null) {
      left.detach();
      newLeft = left;
    }
    if (newRight == null) {
      right.detach();
      newRight = right;
    }
    Node replacement = IR.add(newLeft, newRight);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateDate(Node n) {
    if (!n.isCall() || !n.hasOneChild()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isAdd()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "Date") {
      return n;
    }

    Node replacement = IR.string(dateNow);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateNewDate(Node n) {
    if (!n.isAdd()) {
      return n;
    }

    Node str = n.getLastChild();
    String suffix;
    if (str.isString()) {
      suffix = str.getString();
    } else if (str.isArrayLit()) {
      suffix = "";
    } else {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isNew() || !left.hasTwoChildren()) {
      return n;
    }

    Node date = left.getFirstChild();
    if (!date.isName() || date.getString() != "Date") {
      return n;
    }

    Optional<Integer> opt = tryGetInteger(left.getLastChild());
    if (!opt.isPresent()) {
      return n;
    }

    int num = opt.get();

    Date d = new Date(num);
    String strDate = jsDateFormat.format(d);

    Node replacement = IR.string(strDate + suffix);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateStringSlice(Node n) {
    if (!n.isGetProp() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall()
        || !(parent.hasTwoChildren() || parent.getChildCount() == 3)) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString() || right.getString() != "slice") {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString()) {
      return n;
    }
    String subject = left.getString();

    Node arg = parent.getSecondChild();
    Optional<Integer> b = tryGetInteger(arg);
    if (!b.isPresent()) {
      return n;
    }

    int beginIndex = b.get();
    int endIndex;

    if (parent.hasTwoChildren()) {
      endIndex = subject.length();
    } else {
      Node endArg = parent.getLastChild();
      Optional<Integer> e = tryGetInteger(endArg);
      if (!e.isPresent()) {
        return n;
      }
      endIndex = e.get();
    }

    // https://tc39.github.io/ecma262/#sec-string.prototype.slice
    int len = subject.length();
    if (beginIndex < 0) {
      beginIndex = Math.max(len + beginIndex, 0);
    } else {
      beginIndex = Math.min(beginIndex, len);
    }

    if (endIndex < 0) {
      endIndex = Math.max(len + endIndex, 0);
    } else {
      endIndex = Math.min(endIndex, len);
    }

    int span = Math.max(endIndex - beginIndex, 0);
    String result = subject.substring(beginIndex, beginIndex + span);

    Node replacement = IR.string(result);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private String getNativeDecl(String name) {
    return "function " + name + "() {\n    [native code]\n}";
  }

  private Node tryEvaluateStringToHtmlElement(Node n) {
    if (!isGetPropOrElem(n) || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !(parent.hasOneChild() || parent.hasTwoChildren())) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString() || left.getString() != "") {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }

    Tuple<String, String> affixes = stringToHtml.get(right.getString());
    if (affixes == null) {
      return n;
    }

    String subject;
    if (parent.hasOneChild()) {
      subject = "";
    } else {
      Node arg = parent.getLastChild();
      if (!arg.isString()) {
        return n;
      }

      subject = arg.getString();
    }

    Node replacement = IR.string(affixes.x + subject + affixes.y);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateEscapes(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName()) {
      return n;
    }
    String nameStr = name.getString();
    if (!(nameStr == "escape" || nameStr == "unescape")) {
      return n;
    }
    Boolean isEscape = nameStr == "escape";

    String str;
    Node arg = n.getLastChild();
    if (arg.isString()) {
      str = arg.getString();
    } else if (arg.isGetProp() && arg.hasTwoChildren() && arg.getFirstChild().isArrayLit()) {
      Node second = arg.getLastChild();
      if (!second.isString()) {
        return n;
      }

      String funcName = second.getString();
      if (!PeepholeUnfuck.arrayFunctions.contains(funcName)) {
        return n;
      }
      str = getNativeDecl(funcName);
    } else {
      return n;
    }

    String result;
    try {
      if (isEscape) { // TODO Check if URLEncoder.encode is equivalent to
        // https://tc39.github.io/ecma262/#sec-escape-string
        result = URLEncoder.encode(str, "utf-8");
      } else {
        result = URLDecoder.decode(str, "utf-8");
      }
    } catch (UnsupportedEncodingException e) {
      return n;
    }

    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateIntToStringBase(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node get = n.getFirstChild();
    if (!isGetPropOrElem(get) || !get.hasTwoChildren()) {
      return n;
    }
    Node num = get.getFirstChild();
    if (!num.isNumber()) {
      return n;
    }
    double d = num.getDouble();
    if (d < 0) {
      return n;
    }
    int i = (int) d;
    if (i != d) {
      return n;
    }
    Node op = get.getLastChild();
    if (!op.isString() || op.getString() != "toString") {
      return n;
    }

    Optional<Integer> base = tryGetInteger(n.getLastChild());
    if (!base.isPresent()) {
      return n;
    }
    int b = base.get();
    if (b < 2 || b > 36) {
      return n;
    }

    String result = Integer.toString(i, b);
    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryConstructorNameCoercion(Node n) {
    if (!n.isGetProp()) {
      return n;
    }
    Node left = n.getFirstChild();
    if (!left.isName()) {
      return n;
    }
    String name = left.getString();
    if (!constructors.contains(name)) {
      return n;
    }
    Node right = n.getLastChild();
    if (!right.isString() || right.getString() != "name") {
      return n;
    }

    Node replacement = IR.string(name);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node coerceNaNToString(Node n) {
    if (n.isName() && n.getString() == "NaN") {
      return IR.string("NaN");
    }
    return n;
  }

  private Node tryCoerceNaNObjectLit(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }
    Node left = n.getFirstChild();
    if (!left.isName() || left.getString() != "NaN") {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isObjectLit() || right.hasChildren()) {
      return n;
    }

    Node replacement = IR.string("NaN[object Object]");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryConstructorCoercion(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    Node right = n.getLastChild();
    if (!left.isName() && !right.isName()) {
      return n;
    }

    Node replacement = null;

    if (left.isName() && right.isName()) {
      String leftName = left.getString();
      String rightName = right.getString();
      if (constructors.contains(leftName) && constructors.contains(rightName)) {
        replacement = IR.string(getNativeDecl(leftName) + getNativeDecl(rightName));
      } else if (constructors.contains(leftName)) {
        right.detach();
        replacement = IR.add(IR.string(getNativeDecl(leftName)), coerceNaNToString(right));
      } else if (constructors.contains(rightName)) {
        left.detach();
        replacement = IR.add(coerceNaNToString(left), IR.string(getNativeDecl(rightName)));
      }
    } else if (left.isName()) {
      String leftName = left.getString();
      if (constructors.contains(leftName)) {
        right.detach();
        replacement = IR.add(IR.string(getNativeDecl(leftName)), coerceNaNToString(right));
      }
    } else if (right.isName()) {
      String rightName = right.getString();
      if (constructors.contains(rightName)) {
        left.detach();
        replacement = IR.add(coerceNaNToString(left), IR.string(getNativeDecl(rightName)));
      }
    }

    if (replacement == null) {
      return n;
    }

    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryUnfuckArrayEntries(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    Node call = n.getFirstChild();
    if (!call.isCall() || !call.hasOneChild()) {
      return n;
    }

    Node string = n.getLastChild();
    if (!string.isString()) {
      return n;
    }

    Node getProp = call.getFirstChild();
    if (!getProp.isGetProp() || !getProp.hasTwoChildren()) {
      return n;
    }
    if (!getProp.getFirstChild().isArrayLit()) {
      return n;
    }
    Node entries = getProp.getLastChild();
    if (!entries.isString() || entries.getString() != "entries") {
      return n;
    }

    String suffix = string.getString();
    Node replacement = IR.string("[object Array Iterator]" + suffix);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryFunctionConstructorInvocation(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !parent.hasOneChild()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "Function") {
      return n;
    }
    Node argNode = n.getLastChild();
    if (!argNode.isString()) {
      return n;
    }
    String arg = argNode.getString();
    if (!arg.startsWith("return")) {
      return n;
    }
    String code = arg.substring("return".length()).trim();

    Node replacement = tryEval(code, parent, argNode);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryArrayFunctionConstructorInvocation(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !parent.hasOneChild()) {
      return n;
    }

    Node get1 = n.getFirstChild();
    if (!isGetPropOrElem(get1) || !get1.hasTwoChildren()) {
      return n;
    }
    Node get2 = get1.getFirstChild();
    if (!isGetPropOrElem(get2) || !get2.hasTwoChildren()) {
      return n;
    }
    if (!get2.getFirstChild().isArrayLit()) {
      return n;
    }
    Node filter = get2.getLastChild();
    if (!filter.isString() || !evalArrayFunctions.contains(filter.getString())) {
      return n;
    }

    Node ctor = get1.getLastChild();
    if (!ctor.isString() || ctor.getString() != "constructor") {
      return n;
    }

    Node subject = n.getLastChild();
    if (!subject.isString()) {
      return n;
    }

    String arg = subject.getString();
    if (!arg.startsWith("return")) {
      return n;
    }
    String code = arg.substring("return".length()).trim();

    // We have `[].filter["constructor"]("XX")()`.
    Node replacement = tryEval(code, parent, subject);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryArrayLiteralFunctionStringCoercion(Node n) {
    if (!n.isGetProp() || !n.hasTwoChildren()) {
      return n;
    }

    Node add = n.getParent();
    if (add == null || !add.isAdd()) {
      return n;
    }

    // First operand can be a boolean, string, number or an arraylit `[]`.
    String affix;

    Boolean isReversed = add.getFirstChild() == n;
    Node op = isReversed ? add.getSecondChild() : add.getFirstChild();
    switch (op.getToken()) {
      case TRUE:
        affix = "true";
        break;
      case FALSE:
        affix = "false";
        break;
      case STRING:
        affix = op.getString();
        break;
      case NUMBER:
        affix = PeepholeUnfuck.doubleIntFormat.format(op.getDouble());
        break;
      case ARRAYLIT:
        affix = "";
        break;
      case NAME:
        if (op.getString() == "NaN") {
          affix = "NaN";
          break;
        }
        return n;
      default:
        return n;
    }

    Node left = n.getFirstChild();
    if (!left.isArrayLit()) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }

    String name = right.getString();
    if (!PeepholeUnfuck.arrayFunctions.contains(name)) {
      return n;
    }

    String decl = getNativeDecl(name);

    // We have `X + [].func`.
    String result = isReversed ? decl + affix : affix + decl;
    Node replacement = IR.string(result);
    add.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryStringIndexedString(Node n) {
    if (!n.isGetElem() || !n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString()) {
      return n;
    }
    String leftStr = left.getString();

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }
    int index;
    try {
      index = Integer.parseInt(right.getString());
    } catch (NumberFormatException e) {
      return n;
    }
    if (index < 0 || index >= leftStr.length()) {
      return n;
    }

    // We have `"string"["integer"]`.
    String result = Character.toString(leftStr.charAt(index));
    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryUndefined(Node n) {
    if (!n.isGetElem()) {
      return n;
    }

    if (!n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isArrayLit()) {
      return n;
    }
    if (left.hasChildren()) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isArrayLit()) {
      return n;
    }
    if (right.hasChildren()) {
      return n;
    }

    // We have `[][[]]`.
    Node replacement = IR.name("undefined");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEval(String code, Node codeSourceInfo, Node callSourceInfo) {
    Node replacement;
    if (code == "{}") {
      return IR.objectlit();
    } else if (code.startsWith("/") && code.endsWith("/") && code.length() >= 2) {
      // Possibly regex.
      String regex = code.substring(1, code.length() - 1);
      Node regexArg = IR.string(regex);
      regexArg.useSourceInfoFrom(codeSourceInfo);
      return IR.regexp(regexArg);
    } else if (code.startsWith("\"") && code.endsWith("\"") && code.length() >= 2) {
      // Possibly string.
      String string = code.substring(1, code.length() - 1);
      return IR.string(string);
    } else if (TokenStream.isJSIdentifier(code)) {
      return IR.name(code);
    }

    if (code.startsWith("new Date(") && code.endsWith(")")) {
      String value = code.substring("new Date(".length(), code.length() - 1);
      Node arg = null;
      try {
        double n = Integer.parseInt(value);
        arg = IR.number(n);
      } catch (NumberFormatException e) {
        // NOOP.
      }

      if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 2) {
        arg = IR.string(value.substring(1, value.length() - 1));
      }

      if (arg != null) {
        return IR.newNode(IR.name("Date"), arg);
      }
    }

    try {
      double n = Integer.parseInt(code);
      return IR.number(n);
    } catch (NumberFormatException e) {
      // NOOP.
    }

    Node eval = IR.name("eval");
    eval.putBooleanProp(Node.DIRECT_EVAL, true);
    eval.useSourceInfoFrom(callSourceInfo);

    Node evalArg = IR.string(code);
    evalArg.useSourceInfoFrom(codeSourceInfo);

    replacement = IR.call(eval, evalArg);
    replacement.putBooleanProp(Node.FREE_CALL, true);
    return replacement;
  }

  private Optional<Integer> tryGetInteger(Node arg) {
    double d;
    if (arg.isNumber()) {
      d = arg.getDouble();
    } else if (arg.isString()) {
      String s = arg.getString();

      try {
        d = Double.parseDouble(s);
      } catch (NullPointerException e) {
        return Optional.empty();
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }

    int i = (int) d;
    if (i != d) {
      return Optional.empty();
    }

    return Optional.of(i);
  }

  private Boolean isGetPropOrElem(Node n) {
    return n.isGetProp() || n.isGetElem();
  }
}
