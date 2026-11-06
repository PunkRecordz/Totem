package org.punkrecordz.totem.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.invoke.MethodHandle

internal object NativeDowncalls {

    private val nativeLinker: Linker = Linker.nativeLinker()
    private val criticalOption: Linker.Option = Linker.Option.critical(false)
    private val criticalHeapOption: Linker.Option = Linker.Option.critical(true)

    fun bindStandard(
        symbolName: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = nativeLinker.downcallHandle(resolveSymbol(symbolName), descriptor)

    fun bindCritical(
        symbolName: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = nativeLinker.downcallHandle(resolveSymbol(symbolName), descriptor, criticalOption)

    fun bindCriticalHeap(
        symbolName: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = nativeLinker.downcallHandle(resolveSymbol(symbolName), descriptor, criticalHeapOption)

    private fun resolveSymbol(symbolName: String) =
        NativeLibraryLoader.symbolLookup.find(symbolName).orElseThrow {
            UnsatisfiedLinkError("Symbol $symbolName not found in totem-sys native library")
        }

}
