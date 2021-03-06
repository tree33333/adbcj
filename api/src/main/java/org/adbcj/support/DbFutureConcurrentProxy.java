/*
 *   Copyright (c) 2007 Mike Heath.  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.adbcj.support;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.adbcj.DbException;

public class DbFutureConcurrentProxy<T> extends AbstractDbFutureListenerSupport<T> {

	private Future<T> future;

	private volatile boolean set = false;
	private volatile T value;
	
	public DbFutureConcurrentProxy() {
		// Default constructor
	}
	
	public DbFutureConcurrentProxy(Future<T> future) {
		this.future = future;
	}

	public T get() throws DbException, InterruptedException {
		try {
			if (isDone() && set) {
				return value;
			}
			return future.get();
		} catch (ExecutionException e) {
			throw new DbException(e);
		}
	}

	public T get(long timeout, TimeUnit unit) throws DbException, InterruptedException, TimeoutException {
		try {
			if (isDone() && set) {
				return value;
			}
			return future.get(timeout, unit);
		} catch (ExecutionException e) {
			throw new DbException(e);
		}
	}

	public T getUninterruptably() throws DbException {
		for(;;) {
			boolean interrupted = false;
			try {
				return get();
			} catch (InterruptedException e) {
				interrupted = true;
			} finally {
				if (interrupted) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}
	
	public boolean cancel(boolean mayInterruptIfRunning) {
		boolean canceled = future.cancel(mayInterruptIfRunning);
		if (canceled) {
			setDone();
		}
		return canceled;
	}

	public boolean isCancelled() {
		return future.isCancelled();
	}
	
	@Override
	public boolean isDone() {
		if (super.isDone()) {
			return true;
		}
		return future == null ? false : future.isDone();
	}

	public Future<T> getFuture() {
		return future;
	}

	public void setFuture(Future<T> future) {
		this.future = future;
	}

	public void setValue(T value) {
		getLock().lock();
		try {
			if (isDone()) {
				throw new IllegalStateException("Can't set value when future is done");
			}
			set = true;
			this.value = value;
		} finally {
			getLock().unlock();
		}
	}
	
}
