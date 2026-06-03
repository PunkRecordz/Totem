package org.punkrecordz.totem.tag.contract

interface TagValue<out T> : Tag {

    val value: T

}
