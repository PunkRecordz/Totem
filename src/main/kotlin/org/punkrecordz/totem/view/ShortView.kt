package org.punkrecordz.totem.view

interface ShortView : ArrayView {

    operator fun get(index: Int): Short

    operator fun set(
        index: Int,
        value: Short,
    )

    fun copy(): ShortView

    fun replaceAll(
        target: Short,
        replacement: Short,
    ) {
        for (index in 0 until size) {
            if (get(index) == target) {
                set(index, replacement)
            }
        }
    }

    fun fill(value: Short) {
        for (index in 0 until size) {
            set(index, value)
        }
    }

    /**
     * Translates all 16-bit short values in-place using a lookup table indexed by unsigned short values.
     *
     * Strategy:
     * High-speed bulk translation where each element `x` is replaced with `lookupTable[x & 0xFFFF]`.
     * Native implementations execute this using unrolled SIMD routines across CPU cores.
     */
    fun translate(lookupTable: ShortArray) {
        for (index in 0 until size) {
            val originalValue = get(index).toInt() and 0xFFFF
            if (originalValue < lookupTable.size) {
                set(index, lookupTable[originalValue])
            }
        }
    }

    /**
     * Convenience overload accepting an [IntArray] lookup table.
     */
    fun translate(lookupTable: IntArray) {
        val shortTable = ShortArray(lookupTable.size) { index -> lookupTable[index].toShort() }
        translate(shortTable)
    }

}

