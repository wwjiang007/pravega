package com.emc.nautilus.logclient.impl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.emc.nautilus.common.netty.ClientConnection;
import com.emc.nautilus.common.netty.ConnectionFactory;
import com.emc.nautilus.common.netty.ConnectionFailedException;
import com.emc.nautilus.common.netty.FailingReplyProcessor;
import com.emc.nautilus.common.netty.WireCommands.AppendData;
import com.emc.nautilus.common.netty.WireCommands.AppendSetup;
import com.emc.nautilus.common.netty.WireCommands.DataAppended;
import com.emc.nautilus.common.netty.WireCommands.KeepAlive;
import com.emc.nautilus.common.netty.WireCommands.NoSuchBatch;
import com.emc.nautilus.common.netty.WireCommands.NoSuchSegment;
import com.emc.nautilus.common.netty.WireCommands.SegmentIsSealed;
import com.emc.nautilus.common.netty.WireCommands.SetupAppend;
import com.emc.nautilus.common.netty.WireCommands.WrongHost;
import com.emc.nautilus.common.utils.ReusableLatch;
import com.emc.nautilus.logclient.SegmentOutputStream;
import com.emc.nautilus.logclient.SegmentSealedExcepetion;

import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class SegmentOutputStreamImpl extends SegmentOutputStream {

	private final ConnectionFactory connectionFactory;
	private final String endpoint;
	private final UUID connectionId;
	private final String segment;
	private final State state = new State();
	private final ResponseProcessor responseProcessor = new ResponseProcessor();

	private static final class State {
		private final Object lock = new Object();
		private boolean closed = false;
		private AckListener ackListener; // Held but never called
		private ClientConnection connection;
		private Exception exception = null;
		private final ReusableLatch connectionSetup = new ReusableLatch();
		private final ConcurrentSkipListSet<AppendData> inflight = new ConcurrentSkipListSet<>();
		private final ReusableLatch inflightEmpty = new ReusableLatch(true);
		private long writeOffset = 0;

		private void waitForEmptyInflight() throws InterruptedException {
			inflightEmpty.await();
		}

		private void connectionSetupComplete() {
			connectionSetup.release();
		}
		
		private boolean hasConnetion() {
			return connection != null && connection.isConnected();
		}

		private void newConnection(ClientConnection newConnection) {
			synchronized (lock) {
				connectionSetup.reset();
				exception = null;
				connection = newConnection;
			}
		}

		private void failConnection(Exception e) {
			synchronized (lock) {
				if (exception == null) {
					exception = e;
				}
			}
			connectionSetupComplete();
		}

		private ClientConnection waitForConnection() throws ConnectionFailedException, SegmentSealedExcepetion {
			try {
				connectionSetup.await();
				synchronized (lock) {
					if (exception != null) {
						throw exception;
					}
					return connection;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			} catch (ExecutionException e) {
				throw new ConnectionFailedException(e.getCause());
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (SegmentSealedExcepetion e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		private AppendData createNewInflightAppend(UUID connectionId, String segment, ByteBuffer buff) {
			synchronized (lock) {
				writeOffset += buff.remaining();
				AppendData append = new AppendData(connectionId, writeOffset, Unpooled.wrappedBuffer(buff));
				inflightEmpty.reset();
				inflight.add(append);
				return append;
			}
		}

		private List<AppendData> removeInflightBelow(long connectionOffset) {
			synchronized (lock) {
				ArrayList<AppendData> result = new ArrayList<>();
				for (Iterator<AppendData> iter = inflight.iterator(); iter.hasNext();) {
					AppendData append = iter.next();
					if (append.getConnectionOffset() <= connectionOffset) {
						result.add(append);
						iter.remove();
					} else {
						break;
					}
				}
				if (inflight.isEmpty()) {
					inflightEmpty.release();
				}
				return result;
			}
		}

		private List<AppendData> getAllInflight() {
			synchronized (lock) {
				return new ArrayList<>(inflight);
			}
		}

		private void setAckListener(AckListener callback) {
			synchronized (lock) {
				ackListener = callback;
			}
		}

		private AckListener getAckListener() {
			synchronized (lock) {
				return ackListener;
			}
		}

		private boolean isClosed() {
			synchronized (lock) {
				return closed;
			}
		}

		private void setClosed(boolean closed) {
			synchronized (lock) {
				this.closed = closed;
			}
		}
	}

	private final class ResponseProcessor extends FailingReplyProcessor {

		public void wrongHost(WrongHost wrongHost) {
			state.failConnection(new ConnectionFailedException());// TODO: Probably something else.
		}

		public void segmentIsSealed(SegmentIsSealed segmentIsSealed) {
			state.failConnection(new SegmentSealedExcepetion());
		}

		public void noSuchSegment(NoSuchSegment noSuchSegment) {
			state.failConnection(new IllegalArgumentException(noSuchSegment.toString()));
		}

		public void noSuchBatch(NoSuchBatch noSuchBatch) {
			state.failConnection(new IllegalArgumentException(noSuchBatch.toString()));
		}

		public void dataAppended(DataAppended dataAppended) {
			long ackLevel = dataAppended.getConnectionOffset();
			ackUpTo(ackLevel);
		}

		public void appendSetup(AppendSetup appendSetup) {
			long ackLevel = appendSetup.getConnectionOffsetAckLevel();
			ackUpTo(ackLevel);
			retransmitInflight();
			state.connectionSetupComplete();
		}

		private void ackUpTo(long ackLevel) {
			AckListener ackListener = state.getAckListener();
			for (AppendData toAck : state.removeInflightBelow(ackLevel)) {
				ackListener.ack(toAck.getConnectionOffset());
			}
		}

		private void retransmitInflight() {
			for (AppendData append : state.getAllInflight()) {
				state.connection.send(append);
			}
		}
	}

	@Override
	public void setWriteAckListener(AckListener callback) {
		state.setAckListener(callback);
	}

	@Override
	@Synchronized
	public long write(ByteBuffer buff) throws SegmentSealedExcepetion {
		if (state.isClosed()) {
			throw new IllegalStateException("LogOutputStream was already closed");
		}
		
		ClientConnection connection = connection();
		AppendData append = state.createNewInflightAppend(connectionId, segment, buff);
		connection.send(append);
		
		return append.getConnectionOffset();
	}

	//TODO: This really needs to be fixed to have proper retry with backoff.
	private ClientConnection connection() throws SegmentSealedExcepetion {
		ClientConnection connection;
		while (true) {
			if (!state.hasConnetion()) {
				connection = connectionFactory.establishConnection(endpoint);
				connection.setResponseProcessor(responseProcessor);
				state.newConnection(connection);
				SetupAppend cmd = new SetupAppend(connectionId, segment);
				connection.send(cmd);
			}
			try {
				connection = state.waitForConnection();
				break;
			} catch (ConnectionFailedException e) {
				state.failConnection(e);
				log.warn("Connection failed due to",e);
			}
		}
		return connection;
	}

	@Override
	@Synchronized
	public void close() throws SegmentSealedExcepetion {
		state.setClosed(true);
		flush();
		try {
			state.waitForConnection().drop();
		} catch (ConnectionFailedException e) {
			state.failConnection(e);
		}
	}

	@Override
	@Synchronized
	public void flush() throws SegmentSealedExcepetion {
		try {
			ClientConnection connection = connection();
			connection.send(new KeepAlive());
			state.waitForEmptyInflight();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	@Override
	@Synchronized
	public Future<Long> seal(long timeoutMillis) {
		throw new UnsupportedOperationException();
//		try {
//			flush();
//		} catch (LogSealedExcepetion e) {
//			//TODO: What should we do here?
//			log.error("Asked to seal already sealed connection, that had messages waiting to be flushed."+ segment);
//		}
//		ClientConnection connection;
//		try {
//			connection = state.waitForConnection();
//		} catch (LogSealedExcepetion e) {
//			return null; //TODO: find the current size...
//		} catch (ConnectionFailedException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		connection.send(new SealSegment(segment));
	}

}
