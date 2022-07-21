package com.example.initaudioqueuetest

class Queue<T> {
    private var list = mutableListOf<T?>()
    private var head = 0

    var isEmpty: Boolean = true
        get() = count == 0

    var count: Int = 0
        get() = list.size - head

    fun enqueue(element: T) {
        list.add(element)
    }

    fun dequeue(): T? {
        val element = list[head]
        if (head >= list.size || element == null) {
            return null
        }

        list[head] = null
        head += 1

        val percentage = head.toDouble() / list.size.toDouble()
        if (list.size > 50 && percentage > 0.25) {
            // Throw away first part of list, since it won't be used again.
            list = list.subList(head, list.size - 1)
            head = 0
        }

        return element
    }

    var front: Any? = null
        get() = if (isEmpty) {
            null
        } else {
            list[head]
        }

    fun clear() {
        list.clear()
        head = 0
    }
}