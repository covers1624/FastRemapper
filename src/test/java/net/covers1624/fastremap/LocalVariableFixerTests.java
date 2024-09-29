package net.covers1624.fastremap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.covers1624.fastremap.TestBase.Flags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Created by covers1624 on 21/12/23.
 */
public class LocalVariableFixerTests extends TestBase {

    private final Flags[] FLAGS = { STRIP_FIELDS, STRIP_CTOR, STRIP_CLASS_ATTRS, STRIP_LINE_NUMBERS };
    private final FastRemapper LOCALS_ONLY = new FastRemapper(System.err, List.of(), List.of(), false, false, false, true, false, false, false);

    private static class TestLocalVariableLambdaCollision {

        public void doThing(String a) {
            List.of().forEach(e -> {
            });
        }
    }

    @Test
    public void testLocalVariableLambdaCollision() {
        assertEquals("""
                        // class version 61.0 (61)
                        // access flags 0x20
                        class net/covers1624/fastremap/LocalVariableFixerTests$TestLocalVariableLambdaCollision {


                          // access flags 0x1
                          public doThing(Ljava/lang/String;)V
                           L0
                            INVOKESTATIC java/util/List.of ()Ljava/util/List; (itf)
                            INVOKEDYNAMIC accept()Ljava/util/function/Consumer; [
                              // handle kind 0x6 : INVOKESTATIC
                              java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
                              // arguments:
                              (Ljava/lang/Object;)V,\s
                              // handle kind 0x6 : INVOKESTATIC
                              net/covers1624/fastremap/LocalVariableFixerTests$TestLocalVariableLambdaCollision.lambda$doThing$0(Ljava/lang/Object;)V,\s
                              (Ljava/lang/Object;)V
                            ]
                            INVOKEINTERFACE java/util/List.forEach (Ljava/util/function/Consumer;)V (itf)
                           L1
                            RETURN
                           L2
                            LOCALVARIABLE this Lnet/covers1624/fastremap/LocalVariableFixerTests$TestLocalVariableLambdaCollision; L0 L2 0
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L2 1
                            MAXSTACK = 2
                            MAXLOCALS = 2

                          // access flags 0x100A
                          private static synthetic lambda$doThing$0(Ljava/lang/Object;)V
                           L0
                            RETURN
                           L1
                            LOCALVARIABLE l_param0 Ljava/lang/Object; L0 L1 0
                            MAXSTACK = 0
                            MAXLOCALS = 1
                        }
                        """,
                transform(TestLocalVariableLambdaCollision.class, LOCALS_ONLY, FLAGS)
        );
    }

    private static class TestLambdaLocalVariableNameCapture {

        public void simple(String a) {
            List.of().forEach(e -> {
                System.out.println(a);
            });
        }

        public void multipleVars(String a, String b) {
            List.of().forEach(e -> {
                System.out.println(a);
                System.out.println(b);
            });
        }

        public void nonStaticLambda(String a) {
            List.of().forEach(e -> {
                System.out.println(a);
                System.out.println(this);
            });
        }

        public void nestedLambda(String a, String b) {
            List.of().forEach(e -> {
                System.out.println(a);
                List.of().forEach(e2 -> {
                    System.out.println(b);
                });
            });
        }
    }

    @Test
    public void testLambdaLocalVariableNameCapture() {
        assertEquals("""
                        // class version 61.0 (61)
                        // access flags 0x20
                        class net/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture {


                          // access flags 0x1
                          public simple(Ljava/lang/String;)V
                           L0
                            INVOKESTATIC java/util/List.of ()Ljava/util/List; (itf)
                            ALOAD 1
                            INVOKEDYNAMIC accept(Ljava/lang/String;)Ljava/util/function/Consumer; [
                              // handle kind 0x6 : INVOKESTATIC
                              java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
                              // arguments:
                              (Ljava/lang/Object;)V,\s
                              // handle kind 0x6 : INVOKESTATIC
                              net/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture.lambda$simple$0(Ljava/lang/String;Ljava/lang/Object;)V,\s
                              (Ljava/lang/Object;)V
                            ]
                            INVOKEINTERFACE java/util/List.forEach (Ljava/util/function/Consumer;)V (itf)
                           L1
                            RETURN
                           L2
                            LOCALVARIABLE this Lnet/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture; L0 L2 0
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L2 1
                            MAXSTACK = 2
                            MAXLOCALS = 2

                          // access flags 0x1
                          public multipleVars(Ljava/lang/String;Ljava/lang/String;)V
                           L0
                            INVOKESTATIC java/util/List.of ()Ljava/util/List; (itf)
                            ALOAD 1
                            ALOAD 2
                            INVOKEDYNAMIC accept(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Consumer; [
                              // handle kind 0x6 : INVOKESTATIC
                              java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
                              // arguments:
                              (Ljava/lang/Object;)V,\s
                              // handle kind 0x6 : INVOKESTATIC
                              net/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture.lambda$multipleVars$1(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V,\s
                              (Ljava/lang/Object;)V
                            ]
                            INVOKEINTERFACE java/util/List.forEach (Ljava/util/function/Consumer;)V (itf)
                           L1
                            RETURN
                           L2
                            LOCALVARIABLE this Lnet/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture; L0 L2 0
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L2 1
                            LOCALVARIABLE param1 Ljava/lang/String; L0 L2 2
                            MAXSTACK = 3
                            MAXLOCALS = 3

                          // access flags 0x1
                          public nonStaticLambda(Ljava/lang/String;)V
                           L0
                            INVOKESTATIC java/util/List.of ()Ljava/util/List; (itf)
                            ALOAD 0
                            ALOAD 1
                            INVOKEDYNAMIC accept(Lnet/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture;Ljava/lang/String;)Ljava/util/function/Consumer; [
                              // handle kind 0x6 : INVOKESTATIC
                              java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
                              // arguments:
                              (Ljava/lang/Object;)V,\s
                              // handle kind 0x5 : INVOKEVIRTUAL
                              net/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture.lambda$nonStaticLambda$2(Ljava/lang/String;Ljava/lang/Object;)V,\s
                              (Ljava/lang/Object;)V
                            ]
                            INVOKEINTERFACE java/util/List.forEach (Ljava/util/function/Consumer;)V (itf)
                           L1
                            RETURN
                           L2
                            LOCALVARIABLE this Lnet/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture; L0 L2 0
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L2 1
                            MAXSTACK = 3
                            MAXLOCALS = 2

                          // access flags 0x1
                          public nestedLambda(Ljava/lang/String;Ljava/lang/String;)V
                           L0
                            INVOKESTATIC java/util/List.of ()Ljava/util/List; (itf)
                            ALOAD 1
                            ALOAD 2
                            INVOKEDYNAMIC accept(Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Consumer; [
                              // handle kind 0x6 : INVOKESTATIC
                              java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
                              // arguments:
                              (Ljava/lang/Object;)V,\s
                              // handle kind 0x6 : INVOKESTATIC
                              net/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture.lambda$nestedLambda$4(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V,\s
                              (Ljava/lang/Object;)V
                            ]
                            INVOKEINTERFACE java/util/List.forEach (Ljava/util/function/Consumer;)V (itf)
                           L1
                            RETURN
                           L2
                            LOCALVARIABLE this Lnet/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture; L0 L2 0
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L2 1
                            LOCALVARIABLE param1 Ljava/lang/String; L0 L2 2
                            MAXSTACK = 3
                            MAXLOCALS = 3

                          // access flags 0x100A
                          private static synthetic lambda$nestedLambda$4(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
                           L0
                            GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
                            ALOAD 0
                            INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
                           L1
                            INVOKESTATIC java/util/List.of ()Ljava/util/List; (itf)
                            ALOAD 1
                            INVOKEDYNAMIC accept(Ljava/lang/String;)Ljava/util/function/Consumer; [
                              // handle kind 0x6 : INVOKESTATIC
                              java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
                              // arguments:
                              (Ljava/lang/Object;)V,\s
                              // handle kind 0x6 : INVOKESTATIC
                              net/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture.lambda$nestedLambda$3(Ljava/lang/String;Ljava/lang/Object;)V,\s
                              (Ljava/lang/Object;)V
                            ]
                            INVOKEINTERFACE java/util/List.forEach (Ljava/util/function/Consumer;)V (itf)
                           L2
                            RETURN
                           L3
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L3 0
                            LOCALVARIABLE param1 Ljava/lang/String; L0 L3 1
                            LOCALVARIABLE l_param2 Ljava/lang/Object; L0 L3 2
                            MAXSTACK = 2
                            MAXLOCALS = 3

                          // access flags 0x100A
                          private static synthetic lambda$nestedLambda$3(Ljava/lang/String;Ljava/lang/Object;)V
                           L0
                            GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
                            ALOAD 0
                            INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
                           L1
                            RETURN
                           L2
                            LOCALVARIABLE param1 Ljava/lang/String; L0 L2 0
                            LOCALVARIABLE l2_param1 Ljava/lang/Object; L0 L2 1
                            MAXSTACK = 2
                            MAXLOCALS = 2

                          // access flags 0x1002
                          private synthetic lambda$nonStaticLambda$2(Ljava/lang/String;Ljava/lang/Object;)V
                           L0
                            GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
                            ALOAD 1
                            INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
                           L1
                            GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
                            ALOAD 0
                            INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/Object;)V
                           L2
                            RETURN
                           L3
                            LOCALVARIABLE this Lnet/covers1624/fastremap/LocalVariableFixerTests$TestLambdaLocalVariableNameCapture; L0 L3 0
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L3 1
                            LOCALVARIABLE l_param1 Ljava/lang/Object; L0 L3 2
                            MAXSTACK = 2
                            MAXLOCALS = 3

                          // access flags 0x100A
                          private static synthetic lambda$multipleVars$1(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
                           L0
                            GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
                            ALOAD 0
                            INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
                           L1
                            GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
                            ALOAD 1
                            INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
                           L2
                            RETURN
                           L3
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L3 0
                            LOCALVARIABLE param1 Ljava/lang/String; L0 L3 1
                            LOCALVARIABLE l_param2 Ljava/lang/Object; L0 L3 2
                            MAXSTACK = 2
                            MAXLOCALS = 3

                          // access flags 0x100A
                          private static synthetic lambda$simple$0(Ljava/lang/String;Ljava/lang/Object;)V
                           L0
                            GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
                            ALOAD 0
                            INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
                           L1
                            RETURN
                           L2
                            LOCALVARIABLE param0 Ljava/lang/String; L0 L2 0
                            LOCALVARIABLE l_param1 Ljava/lang/Object; L0 L2 1
                            MAXSTACK = 2
                            MAXLOCALS = 2
                        }
                        """,
                transform(TestLambdaLocalVariableNameCapture.class, LOCALS_ONLY, FLAGS)
        );
    }

}
