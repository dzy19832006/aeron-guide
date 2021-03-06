package com.io7m.aeron_guide.take2;

import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.Header;
import org.agrona.BufferUtil;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conversation between the server and a single client.
 */

public final class EchoServerDuologue implements AutoCloseable
{
  private static final Logger LOG =
    LoggerFactory.getLogger(EchoServerDuologue.class);

  private static final Pattern PATTERN_ECHO =
    Pattern.compile("^ECHO (.*)$");

  private final UnsafeBuffer send_buffer;
  private final EchoServerExecutorService exec;
  private final Instant initial_expire;
  private final InetAddress owner;
  private final int port_data;
  private final int port_control;
  private final int session;
  private final FragmentAssembler handler;
  private boolean closed;
  private Publication publication;
  private Subscription subscription;

  private EchoServerDuologue(
    final EchoServerExecutorService in_exec,
    final Instant in_initial_expire,
    final InetAddress in_owner_address,
    final int in_session,
    final int in_port_data,
    final int in_port_control)
  {
    this.exec =
      Objects.requireNonNull(in_exec, "executor");
    this.initial_expire =
      Objects.requireNonNull(in_initial_expire, "initial_expire");
    this.owner =
      Objects.requireNonNull(in_owner_address, "owner");

    this.send_buffer =
      new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));

    this.session = in_session;
    this.port_data = in_port_data;
    this.port_control = in_port_control;
    this.closed = false;

    this.handler = new FragmentAssembler((data, offset, length, header) -> {
      try {
        this.onMessageReceived(data, offset, length, header);
      } catch (final IOException e) {
        LOG.error("failed to send message: ", e);
        this.close();
      }
    });
  }

  /**
   * Create a new duologue. This will create a new publication and subscription
   * pair using a specific session ID and intended only for a single client
   * at a given address.
   *
   * @param aeron         The Aeron instance
   * @param clock         A clock used for time-related operations
   * @param exec          An executor
   * @param local_address The local address of the server ports
   * @param owner_address The address of the client
   * @param session       The session ID
   * @param port_data     The data port
   * @param port_control  The control port
   *
   * @return A new duologue
   */

  public static EchoServerDuologue create(
    final Aeron aeron,
    final Clock clock,
    final EchoServerExecutorService exec,
    final InetAddress local_address,
    final InetAddress owner_address,
    final int session,
    final int port_data,
    final int port_control)
  {
    Objects.requireNonNull(aeron, "aeron");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(exec, "exec");
    Objects.requireNonNull(local_address, "local_address");
    Objects.requireNonNull(owner_address, "owner_address");

    LOG.debug(
      "creating new duologue at {} ({},{}) session {} for {}",
      local_address,
      Integer.valueOf(port_data),
      Integer.valueOf(port_control),
      Integer.toString(session),
      owner_address);

    final Instant initial_expire =
      clock.instant().plus(10L, ChronoUnit.SECONDS);

    final ConcurrentPublication pub =
      EchoChannels.createPublicationDynamicMDCWithSession(
        aeron,
        local_address,
        port_control,
        EchoServer.ECHO_STREAM_ID,
        session);

    try {
      final EchoServerDuologue duologue =
        new EchoServerDuologue(
          exec,
          initial_expire,
          owner_address,
          session,
          port_data,
          port_control);

      final Subscription sub =
        EchoChannels.createSubscriptionWithHandlersAndSession(
          aeron,
          local_address,
          port_data,
          EchoServer.ECHO_STREAM_ID,
          duologue::onClientConnected,
          duologue::onClientDisconnected,
          session);

      duologue.setPublicationSubscription(pub, sub);
      return duologue;
    } catch (final Exception e) {
      try {
        pub.close();
      } catch (final Exception pe) {
        e.addSuppressed(pe);
      }
      throw e;
    }
  }

  /**
   * Poll the duologue for activity.
   */

  public void poll()
  {
    this.exec.assertIsExecutorThread();
    this.subscription.poll(this.handler, 10);
  }

  private void onMessageReceived(
    final DirectBuffer buffer,
    final int offset,
    final int length,
    final Header header)
    throws IOException
  {
    this.exec.assertIsExecutorThread();

    final String session_name =
      Integer.toString(header.sessionId());
    final String message =
      EchoMessages.parseMessageUTF8(buffer, offset, length);

    /*
     * Try to parse an ECHO message.
     */

    LOG.debug("[{}] received: {}", session_name, message);
    final Matcher echo_matcher = PATTERN_ECHO.matcher(message);
    if (echo_matcher.matches()) {
      EchoMessages.sendMessage(
        this.publication,
        this.send_buffer,
        "ECHO " + echo_matcher.group(1));
      return;
    }

    /*
     * Otherwise, fail and close this duologue.
     */

    try {
      EchoMessages.sendMessage(
        this.publication,
        this.send_buffer,
        "ERROR bad message");
    } finally {
      this.close();
    }
  }

  private void setPublicationSubscription(
    final Publication in_publication,
    final Subscription in_subscription)
  {
    this.publication =
      Objects.requireNonNull(in_publication, "Publication");
    this.subscription =
      Objects.requireNonNull(in_subscription, "Subscription");
  }

  private void onClientDisconnected(
    final Image image)
  {
    this.exec.execute(() -> {
      final int image_session = image.sessionId();
      final String session_name = Integer.toString(image_session);
      final InetAddress address = EchoAddresses.extractAddress(image.sourceIdentity());

      if (this.subscription.imageCount() == 0) {
        LOG.debug("[{}] last client ({}) disconnected", session_name, address);
        this.close();
      } else {
        LOG.debug("[{}] client {} disconnected", session_name, address);
      }
    });
  }

  private void onClientConnected(
    final Image image)
  {
    this.exec.execute(() -> {
      final InetAddress remote_address =
        EchoAddresses.extractAddress(image.sourceIdentity());

      if (Objects.equals(remote_address, this.owner)) {
        LOG.debug("[{}] client with correct IP connected",
                  Integer.toString(image.sessionId()));
      } else {
        LOG.error("connecting client has wrong address: {}",
                  remote_address);
      }
    });
  }

  /**
   * @param now The current time
   *
   * @return {@code true} if this duologue has no subscribers and the current
   * time {@code now} is after the intended expiry date of the duologue
   */

  public boolean isExpired(
    final Instant now)
  {
    Objects.requireNonNull(now, "now");

    this.exec.assertIsExecutorThread();

    return this.subscription.imageCount() == 0
      && now.isAfter(this.initial_expire);
  }

  /**
   * @return {@code true} iff {@link #close()} has been called
   */

  public boolean isClosed()
  {
    this.exec.assertIsExecutorThread();

    return this.closed;
  }

  @Override
  public void close()
  {
    this.exec.assertIsExecutorThread();

    if (!this.closed) {
      try {
        try {
          this.publication.close();
        } finally {
          this.subscription.close();
        }
      } finally {
        this.closed = true;
      }
    }
  }

  /**
   * @return The data port
   */

  public int portData()
  {
    return this.port_data;
  }

  /**
   * @return The control port
   */

  public int portControl()
  {
    return this.port_control;
  }

  /**
   * @return The IP address that is permitted to participate in this duologue
   */

  public InetAddress ownerAddress()
  {
    return this.owner;
  }

  /**
   * @return The session ID of the duologue
   */

  public int session()
  {
    return this.session;
  }
}
