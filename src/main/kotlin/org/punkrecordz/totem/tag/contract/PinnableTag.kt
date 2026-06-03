package org.punkrecordz.totem.tag.contract

interface PinnableTag<out T : Tag> : Tag {

    override fun pin(): T

}
