// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_CIRCULARBUF_HPP
#define CHIAKI_JNI_CIRCULARBUF_HPP

#include "circular-fifo.hpp"

#include <string.h>
#include <assert.h>

#include <android/log.h>

template<size_t MaxChunksCount, size_t ChunkSize>
class CircularBuffer
{
	static_assert(MaxChunksCount > 0, "MaxChunksCount > 0");
	static_assert(ChunkSize > 0, "ChunkSize > 0");

	private:
		using Queue = memory_relaxed_aquire_release::CircularFifo<uint8_t *, MaxChunksCount>;
		Queue full_queue;
		Queue free_queue;
		uint8_t *buffer;
		size_t chunks_count;

		uint8_t *push_chunk;
		size_t push_chunk_size; // written bytes from the start of the chunk

		uint8_t *pop_chunk;
		size_t pop_chunk_size; // remaining bytes until the end of the chunk

		void FlushChunks()
		{
			for(size_t i=1; i<chunks_count; i++)
				free_queue.push(buffer + i * ChunkSize);

			push_chunk = buffer;
			push_chunk_size = 0;

			pop_chunk = nullptr;
			pop_chunk_size = 0;
		}

	public:
		CircularBuffer(size_t chunks_count = MaxChunksCount)
			: buffer(new uint8_t[MaxChunksCount * ChunkSize]), chunks_count(chunks_count)
		{
			assert(chunks_count > 0 && chunks_count <= MaxChunksCount);
			FlushChunks();
		}

		~CircularBuffer()
		{
			delete [] buffer;
		}

		/**
		 * Reset the entire Buffer.
		 * WARNING: Not thread-safe at all! Call only when no producer and consumer is running.
		 */
		void Flush()
		{
			full_queue.reset();
			free_queue.reset();
			FlushChunks();
		}

		/**
		 * Change the active depth while retaining the fixed allocation and lock-free queues.
		 * WARNING: Like Flush(), call only while producer and consumer are stopped.
		 */
		void SetChunksCount(size_t new_chunks_count)
		{
			assert(new_chunks_count > 0 && new_chunks_count <= MaxChunksCount);
			chunks_count = new_chunks_count;
			Flush();
		}

		/**
		 * @return bytes that were pushed
		 */
		size_t Push(uint8_t *buf, size_t buf_size)
		{
			size_t pushed = 0;
			while(pushed < buf_size)
			{
				if(!push_chunk)
				{
					if(!free_queue.pop(push_chunk))
						push_chunk = nullptr;
					if(!push_chunk)
						return pushed;
					push_chunk_size = 0;
				}

				size_t to_push = buf_size - pushed;
				size_t remaining_space = ChunkSize - push_chunk_size;
				if(to_push > remaining_space)
					to_push = remaining_space;
				memcpy(push_chunk + push_chunk_size, buf + pushed, to_push);
				pushed += to_push;
				push_chunk_size += to_push;

				if(push_chunk_size == ChunkSize)
				{
					bool success = full_queue.push(push_chunk);
					assert(success); // We should have made the queues big enough
					push_chunk = nullptr;
				}
			}

			return pushed;
		}

		/**
		 * @return bytes that were popped
		 */
		size_t Pop(uint8_t *buf, size_t buf_size)
		{
			size_t popped = 0;
			while(popped < buf_size)
			{
				if(!pop_chunk)
				{
					if(!full_queue.pop(pop_chunk))
						pop_chunk = nullptr;
					if(!pop_chunk)
						return popped;
					pop_chunk_size = ChunkSize;
				}

				size_t to_pop = buf_size - popped;
				if(to_pop > pop_chunk_size)
					to_pop = pop_chunk_size;
				memcpy(buf + popped, pop_chunk + (ChunkSize - pop_chunk_size), to_pop);
				popped += to_pop;
				pop_chunk_size -= to_pop;

				if(pop_chunk_size == 0)
				{
					bool success = free_queue.push(pop_chunk);
					assert(success);// We should have made the queues big enough
					pop_chunk = nullptr;
				}
			}

			return popped;
		}
};


#endif //CHIAKI_JNI_CIRCULARBUF_HPP
