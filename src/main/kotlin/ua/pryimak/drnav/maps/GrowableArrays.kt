package ua.pryimak.drnav.maps

/** Primitive lists without boxing: all of Ukraine means tens of millions of elements. */
class LongList(capacity: Int = 1024) {
    var data = LongArray(capacity); private set
    var size = 0; private set

    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun toArray(): LongArray = data.copyOf(size)
}

class IntList(capacity: Int = 1024) {
    var data = IntArray(capacity); private set
    var size = 0; private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}

class ByteList(capacity: Int = 1024) {
    var data = ByteArray(capacity); private set
    var size = 0; private set

    fun add(v: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun toArray(): ByteArray = data.copyOf(size)
}
