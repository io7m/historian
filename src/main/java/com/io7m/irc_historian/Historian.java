/*
 * Copyright © 2015 <code@io7m.com> http://io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.io7m.irc_historian;

import com.io7m.jproperties.JProperties;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.UserHostmask;
import org.pircbotx.UtilSSLSocketFactory;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ActionEvent;
import org.pircbotx.hooks.events.ConnectAttemptFailedEvent;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.DisconnectEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NoticeEvent;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.TopicEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

public final class Historian extends ListenerAdapter
{
  private final File log_directory;
  private final String channel;
  private Configuration configuration;

  private Historian(
    final File in_log_directory,
    final String in_channel)
  {
    this.log_directory = Objects.requireNonNull(in_log_directory);
    this.channel = Objects.requireNonNull(in_channel);

    try {
      this.logMessage(
        String.format(
          "self: started: %s",
          Historian.class.getPackage().getImplementationVersion()));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        this.logMessage("self: shutting down");
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }));
  }

  /**
   * The command-line entry point.
   *
   * @param args Command-line arguments
   *
   * @throws Exception On errors
   */

  // CHECKSTYLE:OFF
  public static void main(
    final String[] args)
    throws Exception
  // CHECKSTYLE:ON
  {
    if (args.length < 1) {
      System.err.println("usage: historian.conf");
      System.exit(1);
    }

    final var p = JProperties.fromFile(new File(args[0]));

    final var tls_factory = new UtilSSLSocketFactory();
    tls_factory.trustAllCertificates();

    final var log_dir =
      new File(JProperties.getString(p, "com.io7m.historian.logs"));

    final var cb = new Configuration.Builder();
    cb.setName(JProperties.getString(p, "com.io7m.historian.user"));
    cb.setLogin(JProperties.getString(p, "com.io7m.historian.user"));

    {
      final var pack = Historian.class.getPackage();
      final var s = new StringBuilder();
      s.append(pack.getImplementationTitle());
      s.append("-");
      s.append(pack.getImplementationVersion());
      cb.setVersion(s.toString());
    }

    cb.setSocketFactory(tls_factory);
    cb.setServerHostname(
      JProperties.getString(
        p, "com.io7m.historian.server_address"));
    cb.setServerPort(
      JProperties.getBigInteger(
        p, "com.io7m.historian.server_port").intValue());
    cb.addAutoJoinChannel(
      JProperties.getString(
        p, "com.io7m.historian.channel"));
    cb.setAutoReconnect(true);
    cb.setEncoding(StandardCharsets.UTF_8);

    final var h = new Historian(
      log_dir, JProperties.getString(p, "com.io7m.historian.channel"));
    cb.addListener(h);
    final var c = cb.buildConfiguration();
    h.setConfiguration(c);

    final var bot = new PircBotX(c);
    bot.startBot();
  }

  private static String getUserID(final User u)
  {
    final var login = u.getLogin().isEmpty() ? "-" : u.getLogin();
    final var hostmask = u.getHostmask().isEmpty() ? "-" : u.getHostmask();
    final var nick = u.getNick().isEmpty() ? "-" : u.getNick();
    return String.format("%s@%s/%s", login, hostmask, nick);
  }

  private static String getUserIDFromMask(final UserHostmask u)
  {
    final var login = u.getLogin().isEmpty() ? "-" : u.getLogin();
    final var hostmask = u.getHostmask().isEmpty() ? "-" : u.getHostmask();
    final var nick = u.getNick().isEmpty() ? "-" : u.getNick();
    return String.format("%s@%s/%s", login, hostmask, nick);
  }

  private void logSubjectChange(
    final String subject,
    final String from)
    throws IOException
  {
    final var s = new StringBuilder();
    s.append("topic: ");
    s.append(from);
    s.append(": ");
    s.append(subject);
    this.logMessage(s.toString());
  }

  private synchronized void logMessage(
    final String message)
    throws IOException
  {
    final var c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    final var logyear = new SimpleDateFormat("yyyy");
    final var logmonth = new SimpleDateFormat("MM");
    final var logday = new SimpleDateFormat("dd");

    final var logtime =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SZ");

    final File log_file;
    {
      final var f0 = new File(this.log_directory, this.channel);
      final var f1 = new File(f0, logyear.format(c.getTime()));
      final var f2 = new File(f1, logmonth.format(c.getTime()));
      log_file = new File(f2, logday.format(c.getTime()) + ".txt");
    }

    final var log_dir = log_file.getParentFile();
    log_dir.mkdirs();
    if (!log_dir.isDirectory()) {
      throw new IOException("Could not create log directory: " + log_dir);
    }

    try (var writer =
           new BufferedWriter(
             new OutputStreamWriter(
               new FileOutputStream(log_file, true),
               StandardCharsets.UTF_8))) {
      final var time = logtime.format(c.getTime());
      writer.append(time);
      writer.append(' ');
      writer.append(message);
      writer.append('\n');
      writer.flush();
    }
  }

  @Override
  public void onConnect(
    final ConnectEvent event)
    throws Exception
  {
    super.onConnect(event);
    this.logMessage("self: connected");
  }

  @Override
  public void onConnectAttemptFailed(
    final ConnectAttemptFailedEvent event)
    throws Exception
  {
    super.onConnectAttemptFailed(event);

    event.getConnectExceptions().forEach((address, exception) -> {
      try {
        this.logMessage(
          String.format(
            "self: connection failed: %s - %s",
            address,
            exception.getClass().getCanonicalName(),
            exception.getMessage()));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Override
  public void onDisconnect(
    final DisconnectEvent event)
    throws Exception
  {
    super.onDisconnect(event);

    final var ex = event.getDisconnectException();
    if (ex != null) {
      this.logMessage(
        String.format(
          "self: disconnected: %s - %s",
          ex.getClass().getCanonicalName(),
          ex.getMessage()));
    } else {
      this.logMessage(
        String.format(
          "self: disconnected: (no exception information available)"));
    }
  }

  @Override
  public void onNotice(
    final NoticeEvent event)
    throws Exception
  {
    this.logMessage(
      String.format(
        "notice: %s: %s",
        getUserID(event.getUser()),
        event.getMessage()));
  }

  @Override
  public void onPrivateMessage(
    final PrivateMessageEvent event)
    throws Exception
  {
    this.logMessage(
      String.format(
        "chat: %s: %s",
        getUserID(event.getUser()),
        event.getMessage()));
  }

  @Override
  public void onAction(
    final ActionEvent event)
    throws Exception
  {
    this.logMessage(
      String.format(
        "chat: %s: %s",
        getUserID(event.getUser()),
        "/me " + event.getMessage()));
  }

  @Override
  public void onMessage(
    final MessageEvent event)
    throws Exception
  {
    this.logMessage(
      String.format(
        "chat: %s: %s",
        getUserID(event.getUser()),
        event.getMessage()));
  }

  @Override
  public void onPart(
    final PartEvent event)
    throws Exception
  {
    final var c = Objects.requireNonNull(this.configuration);
    final User user = event.getUser();
    final var me = user.getLogin().equals(c.getLogin());
    if (!me) {
      this.logMessage(
        String.format(
          "status: %s: unavailable (%s)",
          getUserID(user),
          event.getReason()));
    }
  }

  @Override
  public void onJoin(
    final JoinEvent event)
    throws Exception
  {
    final var c = Objects.requireNonNull(this.configuration);
    final var user = event.getUser();
    final var me = user.getLogin().equals(c.getLogin());
    if (!me) {
      this.logMessage(
        String.format(
          "status: %s: available", getUserID(user)));
    } else {
      this.logMessage(
        String.format(
          "self: joined: %s %s",
          event.getChannel().getChannelId(),
          event.getChannel().getName()));
    }
  }

  @Override
  public void onTopic(
    final TopicEvent event)
    throws Exception
  {
    this.logSubjectChange(
      event.getTopic(), getUserIDFromMask(event.getUser()));
  }

  public void setConfiguration(final Configuration in_configuration)
  {
    this.configuration = Objects.requireNonNull(in_configuration);
  }
}
