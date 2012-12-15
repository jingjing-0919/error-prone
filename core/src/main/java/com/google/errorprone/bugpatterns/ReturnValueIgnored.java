/*
 * Copyright 2012 Google Inc. All Rights Reserved.
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

package com.google.errorprone.bugpatterns;

import static com.google.errorprone.BugPattern.Category.JDK;
import static com.google.errorprone.BugPattern.MaturityLevel.MATURE;
import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.kindIs;
import static com.google.errorprone.matchers.Matchers.methodSelect;
import static com.google.errorprone.matchers.Matchers.parentNode;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.DescribingMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author alexeagle@google.com (Alex Eagle)
 */
@BugPattern(name = "ReturnValueIgnored",
    altNames = {"ResultOfMethodCallIgnored"},
    summary = "Ignored return value of method that has no side-effect",
    explanation = "Certain methods have no side effect, so calls to those methods are pointless "
        + "if you ignore the value returned.",
    category = JDK, severity = ERROR, maturity = MATURE)
public class ReturnValueIgnored extends DescribingMatcher<MethodInvocationTree> {

  /**
   * A set of types which this checker should examine method calls on.
   */
  // There are also some high-priority return value ignored checks in FindBugs for various
  // threading constructs which do not return the same type as the receiver.
  // This check does not deal with them, since the fix is less straightforward.
  // See a list of the FindBugs checks here:
  // http://code.google.com/searchframe#Fccnll6ERQ0/trunk/findbugs/src/java/edu/umd/cs/findbugs/ba/CheckReturnAnnotationDatabase.java
  private static final Set<String> typesToCheck = new HashSet<String>(Arrays.asList(
      "java.lang.String", "java.math.BigInteger", "java.math.BigDecimal"));

  /**
   * Matches if the method being called is a statement (rather than an expression), is being
   * called on an instance of a type in the typesToCheck set, and returns the same type
   * (e.g. String.trim() returns a String).
   */
  @SuppressWarnings("unchecked")
  @Override
  public boolean matches(MethodInvocationTree methodInvocationTree, VisitorState state) {
    return allOf(
        parentNode(kindIs(Kind.EXPRESSION_STATEMENT, MethodInvocationTree.class)),
        methodSelect(allOf(methodReceiverHasType(typesToCheck),
            methodReturnsSameTypeAsReceiver()))
    ).matches(methodInvocationTree, state);
  }

  /**
   * Fixes the error by assigning the result of the call to the receiver reference, or deleting
   * the method call.
   */
  @Override
  public Description describe(MethodInvocationTree methodInvocationTree, VisitorState state) {
    // Find the root of the field access chain, i.e. a.intern().trim() ==> a.
    ExpressionTree identifierExpr = ASTHelpers.getRootIdentifier(methodInvocationTree);
    String identifierStr = null;
    Type identifierType = null;
    if (identifierExpr != null) {
      identifierStr = identifierExpr.toString();
      if (identifierExpr instanceof JCIdent) {
        identifierType = ((JCIdent) identifierExpr).sym.type;
      } else if (identifierExpr instanceof JCFieldAccess) {
        identifierType = ((JCFieldAccess) identifierExpr).sym.type;
      } else {
        throw new IllegalStateException("Expected a JCIdent or a JCFieldAccess");
      }
    }

    Type returnType = ASTHelpers.getReturnType(
        ((JCMethodInvocation) methodInvocationTree).getMethodSelect());

    SuggestedFix fix;
    if (identifierStr != null && !"this".equals(identifierStr) && returnType != null &&
        state.getTypes().isAssignable(returnType, identifierType)) {
      // Fix by assigning the assigning the result of the call to the root receiver reference.
      fix = new SuggestedFix().prefixWith(methodInvocationTree, identifierStr + " = ");
    } else {
      // Unclear what the programmer intended.  Should be safe to delete without changing behavior
      // since we expect the method not to have side effects .
      Tree parent = state.getPath().getParentPath().getLeaf();
      fix = new SuggestedFix().delete(parent);
    }
    return new Description(methodInvocationTree, diagnosticMessage, fix);
  }

  public static class Scanner extends com.google.errorprone.Scanner {
    private ReturnValueIgnored matcher = new ReturnValueIgnored();

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, VisitorState visitorState) {
      evaluateMatch(node, visitorState, matcher);
      return super.visitMethodInvocation(node, visitorState);
    }
  }

  /**
   * Matches method invocations that return the same type as the receiver object.
   */
  private static Matcher<ExpressionTree> methodReturnsSameTypeAsReceiver() {
    return new Matcher<ExpressionTree>() {
      @Override
      public boolean matches(ExpressionTree expressionTree, VisitorState state) {
        Type receiverType = getReceiverType(expressionTree);
        Type returnType = ASTHelpers.getReturnType(expressionTree);
        if (receiverType == null || returnType == null) {
          return false;
        }
        return state.getTypes().isSameType(getReceiverType(expressionTree),
            ASTHelpers.getReturnType(expressionTree));
      }
    };
  }

  /**
   * Matches method calls whose receiver objects are of a type included in the set.
   */
  private static Matcher<ExpressionTree> methodReceiverHasType(final Set<String> typeSet) {
    return new Matcher<ExpressionTree>() {
      @Override
      public boolean matches(ExpressionTree expressionTree, VisitorState state) {
        Type receiverType = getReceiverType(expressionTree);
        if (receiverType == null) {
          return false;
        }
        return typeSet.contains(receiverType.toString());
      }
    };
  }

  /**
   * Returns the type of a receiver of a method call expression.
   * Precondition: the expressionTree corresponds to a method call.
   *
   * Examples:
   *    a.b.foo() ==> type of a.b
   *    a.bar().foo() ==> type of a.bar()
   *    this.foo() ==> type of this
   */
  private static Type getReceiverType(ExpressionTree expressionTree) {
    if (expressionTree instanceof JCFieldAccess) {
      JCFieldAccess methodSelectFieldAccess = (JCFieldAccess) expressionTree;
      return ((MethodSymbol) methodSelectFieldAccess.sym).owner.type;
    } else if (expressionTree instanceof JCIdent) {
      JCIdent methodCall = (JCIdent) expressionTree;
      return ((MethodSymbol) methodCall.sym).owner.type;
    }
    return null;
  }
}
