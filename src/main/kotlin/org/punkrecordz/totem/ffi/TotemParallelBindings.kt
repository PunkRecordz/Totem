package org.punkrecordz.totem.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

internal object TotemParallelBindings {

    val decodeVarIntShortsParallel: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_decode_varint_shorts_parallel",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    }

    val varIntPartitionSizes: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_varint_partition_sizes",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )
    }

    val encodeShortsVarIntParallel: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_encode_shorts_varint_parallel",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val replaceShortsParallel: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_replace_shorts_parallel",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_SHORT,
                ValueLayout.JAVA_SHORT,
            ),
        )
    }

    val fillShortsParallel: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_fill_shorts_parallel",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_SHORT,
            ),
        )
    }

    val translateShortsLutParallel: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_translate_shorts_lut_parallel",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )
    }

}
