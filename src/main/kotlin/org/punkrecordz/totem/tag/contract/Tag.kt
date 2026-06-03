package org.punkrecordz.totem.tag.contract

interface Tag {

    val key: TagKey

    fun pin(): Tag

    fun copy(): Tag

}
