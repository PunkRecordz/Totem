package org.punkrecordz.totem.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

internal object TotemSysBindings {

    val decodeVarIntShorts: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "decode_varint_shorts",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    }

    val encodeShortsVarInt: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "encode_shorts_varint",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val varIntEncodedSize: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "varint_encoded_size",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val countVarIntTerminals: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "count_varint_terminals",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val replaceShorts: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "replace_shorts",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_SHORT,
                ValueLayout.JAVA_SHORT,
            ),
        )
    }

    val fillShorts: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "fill_shorts",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_SHORT,
            ),
        )
    }

    val translateShortsLut: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "translate_shorts_lut",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )
    }

    val scanGzipMembers: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "scan_gzip_members",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    }

    val countMatchingShorts: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "count_matching_shorts",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )
    }

    val scanMatchingShorts: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "scan_matching_shorts",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val calculateCompoundSize: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "calculate_compound_size",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val findCompoundEntry: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "find_compound_entry",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_SHORT,
            ),
        )
    }

    val findVarIntBoundaries: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "find_varint_boundaries",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )
    }

    val calculateListSize: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "calculate_list_size",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

}
