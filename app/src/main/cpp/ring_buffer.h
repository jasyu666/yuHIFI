#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

namespace hifi {

/*
 * 单生产者单消费者（SPSC）无锁字节环形缓冲。
 *
 *   生产者 = 解码线程（可以慢，可以偶尔卡一下）
 *   消费者 = USB 提交线程（实时性要求高，绝不加锁、绝不分配内存、绝不阻塞）
 *
 * 容量取 2 的幂，用位掩码代替取模，避免除法。
 *
 * 内存序：写指针用 release，读指针用 acquire。这保证消费者看到 head 更新时，
 * 对应的数据一定已经写完；生产者看到 tail 更新时，那段空间一定已经读走。
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t minCapacity) {
        size_t cap = 1;
        while (cap < minCapacity) cap <<= 1;
        buf_.assign(cap, 0);
        mask_ = cap - 1;
    }

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    /* 可读字节数 */
    size_t available() const {
        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        return h - t;
    }

    /* 可写字节数 */
    size_t space() const {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        return buf_.size() - (h - t);
    }

    /* 由生产者调用。返回实际写入字节数（空间不足时可能少于 bytes）。 */
    size_t write(const uint8_t* src, size_t bytes) {
        const size_t t = tail_.load(std::memory_order_acquire);
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t freeSpace = buf_.size() - (h - t);
        const size_t n = std::min(bytes, freeSpace);
        if (n == 0) return 0;

        const size_t pos = h & mask_;
        const size_t first = std::min(n, buf_.size() - pos);
        std::memcpy(buf_.data() + pos, src, first);
        if (n > first) {
            std::memcpy(buf_.data(), src + first, n - first);
        }

        // release：确保上面 memcpy 的数据对消费者可见
        head_.store(h + n, std::memory_order_release);
        return n;
    }

    /* 由消费者调用。返回实际读出字节数。 */
    size_t read(uint8_t* dst, size_t bytes) {
        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        const size_t avail = h - t;
        const size_t n = std::min(bytes, avail);
        if (n == 0) return 0;

        const size_t pos = t & mask_;
        const size_t first = std::min(n, buf_.size() - pos);
        std::memcpy(dst, buf_.data() + pos, first);
        if (n > first) {
            std::memcpy(dst + first, buf_.data(), n - first);
        }

        /*
         * 用 CAS 提交而不是直接 store：clear() 可能就在刚才这段 memcpy 期间
         * 把 tail_ 挪到了 head_（播放中 seek 时就会发生）。此时我们手里这个
         * t 已经过期，若照样写回 t+n，tail_ 会越过 head_ —— 之后 available()
         * 和 space() 全部按无符号下溢算出天文数字，消费者会读到旧数据、
         * 生产者会以为缓冲是空的。
         *
         * CAS 失败说明这批数据已被判定作废，直接返回 0 让调用方补静音。
         * dst 里已经拷进去的内容会由 RingBufferSource::fill 整块 memset 覆盖掉。
         */
        size_t expected = t;
        if (!tail_.compare_exchange_strong(expected, t + n,
                                           std::memory_order_release,
                                           std::memory_order_relaxed)) {
            return 0;
        }
        return n;
    }

    /*
     * 丢弃当前全部未读数据（切歌、seek）。
     *
     * 可以在播放中调用，与 read() 并发安全 —— 靠 read() 里的 CAS 兜底。
     * 但**不能**与 write() 并发：那会把生产者刚写进去、还没提交 head_ 的
     * 数据一并划走。调用方（AudioEngine::doSeek）是在解码线程里调的，
     * 生产者就是它自己，天然满足这个前提。
     */
    void clear() {
        tail_.store(head_.load(std::memory_order_acquire),
                    std::memory_order_release);
    }

    size_t capacity() const { return buf_.size(); }

private:
    std::vector<uint8_t> buf_;
    size_t mask_ = 0;
    std::atomic<size_t> head_{0};   // 生产者推进
    std::atomic<size_t> tail_{0};   // 消费者推进
};

}  // namespace hifi
