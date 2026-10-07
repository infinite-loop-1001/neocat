package com.neocat.common.queue

import com.neocat.common.queue.impl.BoundedDropQueueFactory
import spock.lang.Specification

/**
 * G0 任务1（红）：有界丢队列契约。
 * 对应 PRD 02 §8：入队满则丢弃，不阻塞业务，丢弃可观测。
 */
class BoundedDropQueueSpec extends Specification {

    QueueFactory factory = new BoundedDropQueueFactory()

    def "offer 在容量未满时成功并入队"() {
        given:
        def queue = factory.create(4)

        expect:
        queue.offer("a")
        queue.offer("b")
        queue.size() == 2
        queue.droppedCount() == 0
    }

    def "offer 在容量已满时立即返回 false 并计入丢弃"() {
        given:
        def queue = factory.create(2)

        when:
        def r1 = queue.offer("a")
        def r2 = queue.offer("b")
        def r3 = queue.offer("c")
        def r4 = queue.offer("d")

        then:
        r1 && r2
        !r3 && !r4
        queue.size() == 2
        queue.droppedCount() == 2
    }

    def "offer 满队列时不阻塞：100 次入队耗时应为毫秒级"() {
        given:
        def queue = factory.create(1)
        queue.offer("only")

        when:
        def startNanos = System.nanoTime()
        100.times { queue.offer("overflow") }
        def elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        then:
        queue.droppedCount() == 100
        elapsedMillis < 500
    }

    def "pollBatch 批量取走且不影响丢弃计数"() {
        given:
        def queue = factory.create(8)
        (1..5).each { queue.offer(it) }

        when:
        def batch = queue.pollBatch(3, 0)

        then:
        batch.size() == 3
        batch == [1, 2, 3]
        queue.size() == 2
        queue.droppedCount() == 0
    }

    def "pollBatch 在空队列上等待超时后返回空列表"() {
        given:
        def queue = factory.create(4)

        when:
        def startNanos = System.nanoTime()
        def batch = queue.pollBatch(10, 50)
        def elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        then:
        batch.isEmpty()
        elapsedMillis >= 40
    }

    def "watermark 反映水位"() {
        given:
        def queue = factory.create(4)

        expect:
        queue.watermark() == 0.0d
        queue.capacity() == 4

        when:
        queue.offer("a")
        queue.offer("b")

        then:
        queue.watermark() == 0.5d
    }
}
