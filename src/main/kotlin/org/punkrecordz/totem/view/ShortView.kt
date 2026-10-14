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

    fun translate(lookupTable: ShortArray) {
        for (index in 0 until size) {
            val originalValue = get(index).toInt() and 0xFFFF
            if (originalValue < lookupTable.size) {
                set(index, lookupTable[originalValue])
            }
        }
    }

    fun translate(lookupTable: IntArray) {
        val shortTable = ShortArray(lookupTable.size) { index -> lookupTable[index].toShort() }
        translate(shortTable)
    }

    fun scanMatchingIndices(shouldIgnore: BooleanArray): IntArray {
        val matches = ArrayList<Int>()

        for (index in 0 until size) {
            val value = get(index).toInt() and 0xFFFF
            if (value < shouldIgnore.size && !shouldIgnore[value]) {
                matches.add(index)
            }
        }

        return matches.toIntArray()
    }

}
