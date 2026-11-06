package org.punkrecordz.totem.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

internal object TotemCodecBindings {

    private val inflateDescriptor: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
    )

    private val deflateDescriptor: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
    )

    private val frameChunkDescriptor: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
    )

    // critical handles skip the thread-state transition and are reserved for bounded, low-latency work
    val inflateCritical: MethodHandle by lazy { NativeDowncalls.bindCritical("totem_inflate", inflateDescriptor) }
    val inflateStandard: MethodHandle by lazy { NativeDowncalls.bindStandard("totem_inflate", inflateDescriptor) }

    val deflateCritical: MethodHandle by lazy { NativeDowncalls.bindCritical("totem_deflate", deflateDescriptor) }
    val deflateStandard: MethodHandle by lazy { NativeDowncalls.bindStandard("totem_deflate", deflateDescriptor) }

    val deflateBound: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "totem_deflate_bound",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
        )
    }

    val frameBound: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "totem_anvil_frame_bound",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
        )
    }

    val frameChunkCritical: MethodHandle by lazy {
        NativeDowncalls.bindCritical("totem_anvil_frame_chunk", frameChunkDescriptor)
    }

    val frameChunkStandard: MethodHandle by lazy {
        NativeDowncalls.bindStandard("totem_anvil_frame_chunk", frameChunkDescriptor)
    }

    val frameChunks: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_anvil_frame_chunks",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    }

    val inflateChunks: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_anvil_inflate_chunks",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    }

    val nativeParallelism: MethodHandle by lazy {
        NativeDowncalls.bindCritical("totem_native_parallelism", FunctionDescriptor.of(ValueLayout.JAVA_INT))
    }

    val gzipSlicesBound: MethodHandle by lazy {
        NativeDowncalls.bindCritical(
            "totem_gzip_slices_bound",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    }

    val gzipCompressSlices: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_gzip_compress_slices",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

    val gzipInflateMembers: MethodHandle by lazy {
        NativeDowncalls.bindStandard(
            "totem_gzip_inflate_members",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )
    }

}
