package arrow.fx.coroutines

import arrow.core.Either
import arrow.core.Tuple4
import arrow.core.Tuple5
import arrow.core.Tuple6
import arrow.core.Tuple7
import arrow.core.Tuple8
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Tuples [fa], [fb] in parallel on [ComputationPool].
 * Cancelling this operation cancels both operations running in parallel.
 *
 * @see parTupledN for the same function that can race on any [CoroutineContext].
 */
suspend fun <A, B> parTupledN(fa: suspend () -> A, fb: suspend () -> B): Pair<A, B> =
  parTupledN(ComputationPool, fa, fb)

/**
 * Tuples [fa], [fb], [fc] in parallel on [ComputationPool].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * @see parTupledN for the same function that can race on any [CoroutineContext].
 */
suspend fun <A, B, C> parTupledN(fa: suspend () -> A, fb: suspend () -> B, fc: suspend () -> C): Triple<A, B, C> =
  parTupledN(ComputationPool, fa, fb, fc)

/**
 * Tuples [fa], [fb] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** it runs in parallel depending on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parTupledN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B> parTupledN(ctx: CoroutineContext, fa: suspend () -> A, fb: suspend () -> B): Pair<A, B> =
  parMapN(ctx, fa, fb, ::Pair)

/**
 * Tuples [fa], [fb] & [fc] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** it runs in parallel depending on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parTupledN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C> parTupledN(ctx: CoroutineContext, fa: suspend () -> A, fb: suspend () -> B, fc: suspend () -> C): Triple<A, B, C> =
  parMapN(ctx, fa, fb, fc, ::Triple)

/**
 * Parallel maps [fa], [fb] in parallel on [ComputationPool].
 * Cancelling this operation cancels both operations running in parallel.
 *
 * ```kotlin:ank:playground
 * import arrow.fx.coroutines.*
 *
 * suspend fun main(): Unit {
 *   //sampleStart
 *   val result = parMapN(
 *     { "First one is on ${Thread.currentThread().name}" },
 *     { "Second one is on ${Thread.currentThread().name}" }
 *   ) { a, b ->
 *       "$a\n$b"
 *     }
 *   //sampleEnd
 *  println(result)
 * }
 * ```
 *
 * @param fa value to parallel map
 * @param fb value to parallel map
 * @param f function to map/combine value [A] and [B]
 * ```
 *
 * @see parMapN for the same function that can race on any [CoroutineContext].
 */
suspend fun <A, B, C> parMapN(fa: suspend () -> A, fb: suspend () -> B, f: (A, B) -> C): C =
  parMapN(ComputationPool, fa, fb, f)

/**
 * Parallel maps [fa], [fb], [fc] in parallel on [ComputationPool].
 * Cancelling this operation cancels both operations running in parallel.
 *
 * @see parMapN for the same function that can race on any [CoroutineContext].
 */
suspend fun <A, B, C, D> parMapN(
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  f: (A, B, C) -> D
): D =
  parMapN(
    suspend { parMapN(fa, fb, ::Pair) },
    fc
  ) { ab, c ->
    val (a, b) = ab
    f(a, b, c)
  }

/**
 * Parallel maps [fa], [fb] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
@Suppress("UNCHECKED_CAST")
suspend fun <A, B, C> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  f: (A, B) -> C
): C =
  suspendCoroutineUninterceptedOrReturn { _cont ->
    val conn = _cont.context.connection()
    val cont = _cont.intercepted()
    val cb = cont::resumeWith

    // Used to store Throwable, Either<A, B> or empty (null). (No sealed class used for a slightly better performing ParMap2)
    val state = AtomicRefW<Any?>(null)

    val connA = SuspendConnection()
    val connB = SuspendConnection()

    conn.pushPair(connA, connB)

    fun complete(a: A, b: B) {
      conn.pop()
      cb(
        try {
          Result.success(f(a, b))
        } catch (e: Throwable) {
          Result.failure<C>(e.nonFatalOrThrow())
        }
      )
    }

    fun sendException(other: SuspendConnection, e: Throwable) = when (state.getAndSet(e)) {
      is Throwable -> Unit // Do nothing we already finished
      else -> other.cancelToken().cancel.startCoroutine(Continuation(EmptyCoroutineContext) { r ->
        conn.pop()
        cb(Result.failure(r.fold({ e }, { e2 -> Platform.composeErrors(e, e2) })))
      })
    }

    fa.startCoroutineCancellable(CancellableContinuation(ctx, connA) { resA ->
      resA.fold({ a ->
        when (val oldState = state.getAndSet(Either.Left(a))) {
          null -> Unit // Wait for B
          is Throwable -> Unit // ParMapN already failed and A was cancelled.
          is Either.Left<*> -> Unit // Already state.getAndSet
          is Either.Right<*> -> complete(a, (oldState as Either.Right<B>).b)
        }
      }, { e ->
        sendException(connB, e)
      })
    })

    fb.startCoroutineCancellable(CancellableContinuation(ctx, connB) { resB ->
      resB.fold({ b ->
        when (val oldState = state.getAndSet(Either.Right(b))) {
          null -> Unit // Wait for A
          is Throwable -> Unit // ParMapN already failed and B was cancelled.
          is Either.Right<*> -> Unit // IO cannot finish twice
          is Either.Left<*> -> complete((oldState as Either.Left<A>).a, b)
        }
      }, { e ->
        sendException(connA, e)
      })
    })

    COROUTINE_SUSPENDED
  }

/**
 * Parallel maps [fa], [fb], [fc] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb] & [fc] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C, D> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  f: (A, B, C) -> D
): D =
  suspendCoroutineUninterceptedOrReturn { _cont ->
    val conn = _cont.context.connection()
    val cont = _cont.intercepted()
    val cb = cont::resumeWith

    val state: AtomicRefW<Triple<A?, B?, C?>?> = AtomicRefW(null)
    val active = AtomicBooleanW(true)

    val connA = SuspendConnection()
    val connB = SuspendConnection()
    val connC = SuspendConnection()

    // Composite cancellable that cancels all ops.
    // NOTE: conn.pop() called when cb gets called below in complete.
    conn.push(listOf(connA.cancelToken(), connB.cancelToken(), connC.cancelToken()))

    fun complete(a: A, b: B, c: C) {
      conn.pop()
      cb(Result.success(f(a, b, c)))
    }

    fun tryComplete(result: Triple<A?, B?, C?>?): Unit {
      result?.let { (a, b, c) ->
        a?.let {
          b?.let {
            c?.let {
              complete(a, b, c)
            }
          }
        }
      } ?: Unit
    }

    fun sendException(other: SuspendConnection, other2: SuspendConnection, e: Throwable) =
      if (active.getAndSet(false)) { // We were already cancelled so don't do anything.
        other.cancelToken().cancel.startCoroutine(Continuation(EmptyCoroutineContext) { r1 ->
          other2.cancelToken().cancel.startCoroutine(Continuation(EmptyCoroutineContext) { r2 ->
            conn.pop()
            cb(Result.failure(r1.fold({
              r2.fold({ e }, { e3 -> Platform.composeErrors(e, e3) })
            }, { e2 ->
              r2.fold({ Platform.composeErrors(e, e2) }, { e3 -> Platform.composeErrors(e, e2, e3) })
            })))
          })
        })
      } else Unit

    // Can be started with a `startCancellableCoroutine` builder instead.
    fa.startCoroutineCancellable(CancellableContinuation(ctx, connA) { resA ->
      resA.fold({ a ->
        val newState = state.updateAndGet { current ->
          current?.copy(first = a) ?: Triple(a, null, null)
        }
        tryComplete(newState)
      }, { e ->
        sendException(connB, connC, e)
      })
    })

    fb.startCoroutineCancellable(CancellableContinuation(ctx, connB) { resB ->
      resB.fold({ b ->
        val newState = state.updateAndGet { current ->
          current?.copy(second = b) ?: Triple(null, b, null)
        }
        tryComplete(newState)
      }, { e ->
        sendException(connA, connC, e)
      })
    })

    fc.startCoroutineCancellable(CancellableContinuation(ctx, connC) { resC ->
      resC.fold({ c ->
        val newState = state.updateAndGet { current ->
          current?.copy(third = c) ?: Triple(null, null, c)
        }
        tryComplete(newState)
      }, { e ->
        sendException(connA, connC, e)
      })
    })

    COROUTINE_SUSPENDED
  }

/**
 * Parallel maps [fa], [fb], [fc], [fd] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb], [fc], [fd] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C, D, E> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  fd: suspend () -> D,
  f: (A, B, C, D) -> E
): E =
  parMapN(
    ctx,
    suspend { parMapN(ctx, fa, fb, fc, ::Triple) },
    fd
  ) { abc, d ->
    val (a, b, c) = abc
    f(a, b, c, d)
  }

/**
 * Parallel maps [fa], [fb], [fc], [fd], [fe] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb], [fc], [fd], [fe] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C, D, E, F> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  fd: suspend () -> D,
  fe: suspend () -> E,
  f: (A, B, C, D, E) -> F
): F =
  parMapN(
    ctx,
    suspend { parMapN(ctx, fa, fb, fc, fd, ::Tuple4) },
    fe
  ) { abcd, e ->
    val (a, b, c, d) = abcd
    f(a, b, c, d, e)
  }

/**
 * Parallel maps [fa], [fb], [fc], [fd], [fe], [ff] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb], [fc], [fd], [fe], [ff] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C, D, E, F, G> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  fd: suspend () -> D,
  fe: suspend () -> E,
  ff: suspend () -> F,
  f: (A, B, C, D, E, F) -> G
): G =
  parMapN(
    ctx,
    suspend { parMapN(ctx, fa, fb, fc, fd, fe, ::Tuple5) },
    ff
  ) { abcde, _f ->
    val (a, b, c, d, e) = abcde
    f(a, b, c, d, e, _f)
  }

/**
 * Parallel maps [fa], [fb], [fc], [fd], [fe], [ff], [fg] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb], [fc], [fd], [fe], [ff], [fg] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C, D, E, F, G, H> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  fd: suspend () -> D,
  fe: suspend () -> E,
  ff: suspend () -> F,
  fg: suspend () -> G,
  f: (A, B, C, D, E, F, G) -> H
): H =
  parMapN(
    ctx,
    suspend { parMapN(ctx, fa, fb, fc, fd, fe, ff, ::Tuple6) },
    fg
  ) { abcdef, g ->
    val (a, b, c, d, e, _f) = abcdef
    f(a, b, c, d, e, _f, g)
  }

/**
 * Parallel maps [fa], [fb], [fc], [fd], [fe], [ff], [fg], [fh] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb], [fc], [fd], [fe], [ff], [fg], [fh] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C, D, E, F, G, H, I> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  fd: suspend () -> D,
  fe: suspend () -> E,
  ff: suspend () -> F,
  fg: suspend () -> G,
  fh: suspend () -> H,
  f: (A, B, C, D, E, F, G, H) -> I
): I =
  parMapN(
    ctx,
    suspend { parMapN(ctx, fa, fb, fc, fd, fe, ff, fg, ::Tuple7) },
    fh
  ) { abcdefg, h ->
    val (a, b, c, d, e, _f, g) = abcdefg
    f(a, b, c, d, e, _f, g, h)
  }
/**
 * Parallel maps [fa], [fb], [fc], [fd], [fe], [ff], [fg], [fh], [fi] on the provided [CoroutineContext].
 * Cancelling this operation cancels both tasks running in parallel.
 *
 * **WARNING** this function forks [fa], [fb], [fc], [fd], [fe], [ff], [fg], [fh], [fi] but if it runs in parallel depends
 * on the capabilities of the provided [CoroutineContext].
 * We ensure they start in sequence so it's guaranteed to finish on a single threaded context.
 *
 * @see parMapN for a function that ensures it runs in parallel on the [ComputationPool].
 */
suspend fun <A, B, C, D, E, F, G, H, I, J> parMapN(
  ctx: CoroutineContext,
  fa: suspend () -> A,
  fb: suspend () -> B,
  fc: suspend () -> C,
  fd: suspend () -> D,
  fe: suspend () -> E,
  ff: suspend () -> F,
  fg: suspend () -> G,
  fh: suspend () -> H,
  fi: suspend () -> I,
  f: (A, B, C, D, E, F, G, H, I) -> J
): J =
  parMapN(
    ctx,
    suspend { parMapN(ctx, fa, fb, fc, fd, fe, ff, fg, fh) { a, b, c, d, e, _f, g, h -> Tuple8(a, b, c, d, e, _f, g, h) } },
    fi
  ) { abcdefgh, i ->
    val (a, b, c, d, e, _f, g, h) = abcdefgh
    f(a, b, c, d, e, _f, g, h, i)
  }
