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

import com.io7m.jnull.NullCheck;
import com.io7m.jproperties.JProperties;
import com.io7m.jproperties.JPropertyIncorrectType;
import com.io7m.jproperties.JPropertyNonexistent;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.UserHostmask;
import org.pircbotx.UtilSSLSocketFactory;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ActionEvent;
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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Properties;
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
    this.log_directory = NullCheck.notNull(in_log_directory);
    this.channel = NullCheck.notNull(in_channel);
  }

  public static void main(
    final String[] args)
    throws
    IOException,
    IrcException,
    JPropertyNonexistent,
    JPropertyIncorrectType
  {
    if (args.length < 1) {
      System.err.println("usage: historian.conf");
      System.exit(1);
    }

    final Properties p = JProperties.fromFile(new File(args[0]));

    final UtilSSLSocketFactory tls_factory = new UtilSSLSocketFactory();
    tls_factory.trustAllCertificates();

    final File log_dir =
      new File(JProperties.getString(p, "com.io7m.historian.logs"));

    final Configuration.Builder cb = new Configuration.Builder();
    cb.setName(JProperties.getString(p, "com.io7m.historian.user"));
    cb.setLogin(JProperties.getString(p, "com.io7m.historian.user"));

    {
      final Package pack = Historian.class.getPackage();
      final StringBuilder s = new StringBuilder();
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

    final Historian h = new Historian(
      log_dir, JProperties.getString(p, "com.io7m.historian.channel"));
    cb.addListener(h);
    final Configuration c = cb.buildConfiguration();
    h.setConfiguration(c);

    final PircBotX bot = new PircBotX(c);
    bot.startBot();
  }

  private static String getUserID(final User u)
  {
    final String login = u.getLogin().isEmpty() ? "-" : u.getLogin();
    final String hostmask = u.getHostmask().isEmpty() ? "-" : u.getHostmask();
    final String nick = u.getNick().isEmpty() ? "-" : u.getNick();
    return String.format("%s@%s/%s", login, hostmask, nick);
  }

  private static String getUserIDFromMask(final UserHostmask u)
  {
    final String login = u.getLogin().isEmpty() ? "-" : u.getLogin();
    final String hostmask = u.getHostmask().isEmpty() ? "-" : u.getHostmask();
    final String nick = u.getNick().isEmpty() ? "-" : u.getNick();
    return String.format("%s@%s/%s", login, hostmask, nick);
  }

  private void logSubjectChange(
    final String subject,
    final String from)
    throws IOException
  {
    final StringBuilder s = new StringBuilder();
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
    final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    final SimpleDateFormat logyear = new SimpleDateFormat("yyyy");
    final SimpleDateFormat logmonth = new SimpleDateFormat("MM");
    final SimpleDateFormat logday = new SimpleDateFormat("dd");

    final SimpleDateFormat logtime =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SZ");

    final File log_file;
    {
      final File f0 = new File(this.log_directory, this.channel);
      final File f1 = new File(f0, logyear.format(c.getTime()));
      final File f2 = new File(f1, logmonth.format(c.getTime()));
      log_file = new File(f2, logday.format(c.getTime()) + ".txt");
    }

    final File log_dir = log_file.getParentFile();
    log_dir.mkdirs();
    if (log_dir.isDirectory() == false) {
      throw new IOException("Could not create log directory: " + log_dir);
    }

    try (final BufferedWriter writer =
           new BufferedWriter(
             new OutputStreamWriter(
               new FileOutputStream(log_file, true),
               StandardCharsets.UTF_8))) {
      final String time = logtime.format(c.getTime());
      writer.append(time);
      writer.append(' ');
      writer.append(message);
      writer.append('\n');
      writer.flush();
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
        Historian.getUserID(event.getUser()),
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
        Historian.getUserID(event.getUser()),
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
        Historian.getUserID(event.getUser()),
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
        Historian.getUserID(event.getUser()),
        event.getMessage()));
  }

  @Override
  public void onPart(
    final PartEvent event)
    throws Exception
  {
    final Configuration c = NullCheck.notNull(this.configuration);
    final User user = event.getUser();
    final boolean me = user.getLogin().equals(c.getLogin());
    if (me == false) {
      this.logMessage(
        String.format(
          "status: %s: unavailable (%s)",
          Historian.getUserID(user),
          event.getReason()));
    }
  }

  @Override
  public void onJoin(
    final JoinEvent event)
    throws Exception
  {
    final Configuration c = NullCheck.notNull(this.configuration);
    final User user = event.getUser();
    final boolean me = user.getLogin().equals(c.getLogin());
    if (me == false) {
      this.logMessage(
        String.format(
          "status: %s: available", Historian.getUserID(user)));
    }
  }

  @Override
  public void onTopic(
    final TopicEvent event)
    throws Exception
  {
    this.logSubjectChange(
      event.getTopic(), Historian.getUserIDFromMask(event.getUser()));
  }

  public void setConfiguration(final Configuration in_configuration)
  {
    this.configuration = NullCheck.notNull(in_configuration);
  }
}
