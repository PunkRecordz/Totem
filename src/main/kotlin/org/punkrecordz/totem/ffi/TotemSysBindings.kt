package org.punkrecordz.totem.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

internal object TotemSysBindings {

    private val nativeLinker: Linker = Linker.nativeLinker()
    private val criticalOption: Linker.Option = Linker.Option.critical(false)

    private fun bindCritical(
        symbolName: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle {
        val symbol = NativeLibraryLoader.symbolLookup.find(symbolName)
            .orElseThrow { UnsatisfiedLinkError("Symbol $symbolName not found in totem-sys native library") }

        return nativeLinker.downcallHandle(symbol, descriptor, criticalOption)
    }

    val decodeVarIntShorts: MethodHandle by lazy {
        bindCritical(
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
        bindCritical(
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
        bindCritical(
            "varint_encoded_size",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val countVarIntTerminals: MethodHandle by lazy {
        bindCritical(
            "count_varint_terminals",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val replaceShorts: MethodHandle by lazy {
        bindCritical(
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
        bindCritical(
            "fill_shorts",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_SHORT,
            ),
        )
    }

    val translateShortsLut: MethodHandle by lazy {
        bindCritical(
            "translate_shorts_lut",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )
    }

    val scanGzipMembers: MethodHandle by lazy {
        bindCritical(
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
        bindCritical(
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
        bindCritical(
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
        bindCritical(
            "calculate_compound_size",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val findCompoundEntry: MethodHandle by lazy {
        bindCritical(
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
        bindCritical(
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
        bindCritical(
            "calculate_list_size",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

}
