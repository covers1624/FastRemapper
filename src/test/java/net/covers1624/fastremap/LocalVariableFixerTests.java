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
    private final FastRemapper LOCALS_ONLY = new FastRemapper(System.err, List.of(), List.of(), false, false, true, false, false, false);

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
}
