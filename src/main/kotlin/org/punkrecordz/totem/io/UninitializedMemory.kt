package org.punkrecordz.totem.io

import java.lang.foreign.*

private val linker = Linker.nativeLinker()
private val stdlib = linker.defaultLookup()

private val malloc = linker.downcallHandle(
    stdlib.find("malloc").orElseThrow { IllegalStateException("Cannot find 'malloc' in native standard library.") },
    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
)

private val free = linker.downcallHandle(
    stdlib.find("free").orElseThrow { IllegalStateException("Cannot find 'free' in native standard library.") },
    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
)

fun Arena.allocateUninitialized(byteSize: Long): MemorySegment {
    require(byteSize >= 0) {
        "Allocation size must be positive: byteSize=$byteSize"
    }

    if (byteSize == 0L) {
        return MemorySegment.NULL
    }

    val rawAddress = malloc.invoke(byteSize) as MemorySegment

    if (rawAddress == MemorySegment.NULL) {
        throw OutOfMemoryError("Native malloc failed to allocate $byteSize bytes.")
    }

    return rawAddress.reinterpret(byteSize, this) { address ->
        try {
            free.invoke(address)
        } catch (exception: Throwable) {
            exception.printStackTrace()
        }
    }
}
