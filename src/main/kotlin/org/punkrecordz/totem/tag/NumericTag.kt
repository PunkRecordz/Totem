package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.TagValue

interface NumericTag<out T : Number> : TagValue<T>
