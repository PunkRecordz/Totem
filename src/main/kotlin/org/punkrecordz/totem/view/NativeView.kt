package org.punkrecordz.totem.view

import java.lang.foreign.MemorySegment

interface NativeView {

    val segment: MemorySegment

}
