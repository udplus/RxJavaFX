/**
 * Copyright 2016 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.schedulers;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;
import rx.Scheduler;
import rx.Subscription;
import rx.functions.Action0;
import rx.subscriptions.Subscriptions;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes work on the JavaFx UI thread.
 * This scheduler should only be used with actions that execute quickly.
 */
public final class JavaFxScheduler extends Scheduler {
	private static final JavaFxScheduler INSTANCE = new JavaFxScheduler();

	/* package for unit test */JavaFxScheduler() {
	}

	public static JavaFxScheduler getInstance() {
		return INSTANCE;
	}

	public static JavaFxScheduler platform() {
		return INSTANCE;
	}

	private static void assertThatTheDelayIsValidForTheJavaFxTimer(long delay) {
		if (delay < 0 || delay > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(String.format("The JavaFx timer only accepts non-negative delays up to %d milliseconds.", Integer.MAX_VALUE));
		}
	}

	@Override
	public Worker createWorker() {
		return new JavaFxWorker();
	}

	/**
	 * A Worker implementation which manages a queue of QueuedRunnable for execution on the Java FX Application thread
	 * For a simpler implementation the queue always contains at least one element.
	 * {@link #head} is the element, which is in execution or was last executed
	 * {@link #tail} is an atomic reference to the last element in the queue, or null when the worker was disposed
	 * Recursive actions are not preferred and inserted at the tail of the queue as any other action would be
	 * The Worker will only schedule a single job with {@link Platform#runLater(Runnable)} for when the queue was previously empty
	 */
	private static class JavaFxWorker extends Worker implements Runnable {
		private volatile QueuedRunnable                  head = new QueuedRunnable(null); /// only advanced in run(), initialised with a starter element
		private final    AtomicReference<QueuedRunnable> tail = new AtomicReference<>(head); /// points to the last element, null when disposed

		private static class QueuedRunnable extends AtomicReference<QueuedRunnable> implements Subscription, Action0 {
			private volatile Action0 action;

			private QueuedRunnable(Action0 action) {
				this.action = action;
			}

			@Override
			public void unsubscribe() {
				action = null;
			}

			@Override
			public boolean isUnsubscribed() {
				return action == null;
			}

			@Override
			public void call() {
				Action0 action = this.action;
				if (action != null) {
					action.call();
				}
				this.action = null;
			}
		}

		@Override
		public void unsubscribe() {
			tail.set(null);
			QueuedRunnable qr = this.head;
			while (qr != null) {
				qr.unsubscribe();
				qr = qr.getAndSet(null);
			}
		}

		@Override
		public boolean isUnsubscribed() {
			return tail.get() == null;
		}

		@Override
		public Subscription schedule(final Action0 action, long delayTime, TimeUnit unit) {
			long delay = Math.max(0, unit.toMillis(delayTime));
			assertThatTheDelayIsValidForTheJavaFxTimer(delay);

			final QueuedRunnable queuedRunnable = new QueuedRunnable(action);
			if (delay == 0) { // delay is too small for the java fx timer, schedule it without delay
				return schedule(queuedRunnable);
			}

			final Timeline timer = new Timeline(new KeyFrame(Duration.millis(delay), event -> schedule(queuedRunnable)));
			timer.play();

			return Subscriptions.create(() -> {
				queuedRunnable.unsubscribe();
				timer.stop();
			});
		}

		@Override
		public Subscription schedule(final Action0 action) {
			if (isUnsubscribed()) {
				return Subscriptions.unsubscribed();
			}

			final QueuedRunnable queuedRunnable = action instanceof QueuedRunnable ? (QueuedRunnable) action : new QueuedRunnable(action);

			QueuedRunnable tailPivot;
			do {
				tailPivot = tail.get();
			} while (tailPivot != null && !tailPivot.compareAndSet(null, queuedRunnable));

			if (tailPivot == null) {
				queuedRunnable.unsubscribe();
			} else {
				tail.compareAndSet(tailPivot, queuedRunnable); // can only fail with a concurrent dispose and we don't want to override the disposed value
				if (tailPivot == head) {
					if (Platform.isFxApplicationThread()) {
						run();
					} else {
						Platform.runLater(this);
					}
				}
			}
			return queuedRunnable;
		}

		@Override
		public void run() {
			for (QueuedRunnable qr = head.get(); qr != null; qr = qr.get()) {
				qr.call();
				head = qr;
			}
		}
	}
}
