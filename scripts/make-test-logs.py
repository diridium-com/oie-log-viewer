#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 Diridium Technologies Inc.
"""Write realistic OIE log files for testing the log viewer by hand.

Produces lines in the engine's shipped layout
(``%-5p %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c: %m%n``) with the things that
make real logs hard to read: Java stack traces with ``Caused by:`` chains and
``... N more``, HL7 messages logged by channels (segments separated by lone
carriage returns), long lines, and optionally Windows CRLF line ends.

It writes the active file and zip archives the way log4j's default rollover
leaves them: ``mirth.log`` plus ``mirth.log.N.zip``, each zip holding one
entry, with the highest index newest and modification times in order.
Deterministic for a given --seed, so a bug report can name the data.

It OVERWRITES mirth.log and mirth.log.N.zip in --dir without asking. Use it
on a scratch directory, or on the logs directory of a stopped test engine whose
logs you do not need; never on an engine whose logs matter.

Example:
    scripts/make-test-logs.py --dir /tmp/test-logs --active-mb 3 --archives 5 --archive-mb 2
"""

import argparse
import datetime
import os
import random
import zipfile

CHANNELS = [("ADT Inbound", "6b1a1f52-0d3c-4f8e-9a51-3f0c9b0d2a11"),
            ("Lab Results Outbound", "a3c4e9d0-7b2f-4c1e-8d7a-55e2f1b0c9d3"),
            ("Pharmacy Orders", "0f9e8d7c-6b5a-4392-8170-fedcba987654")]
LOGGERS = ["com.mirth.connect.server.Mirth", "com.mirth.connect.server.controllers.DonkeyEngineController",
           "transformer", "filter", "db-connector", "js-connector", "response", "postprocessor"]
INFO_MESSAGES = ["Channel deployed.", "Received message, building response.", "Database poll returned 12 rows.",
                 "Sending ACK.", "Mapped patient to MRN 000{n}.", "Connection established to 10.20.30.40:6661."]
WARN_MESSAGES = ["Response took {n} ms, longer than the 2000 ms threshold.",
                 "Queue size for destination 1 is {n}.", "Retrying connection, attempt {n} of 3."]
EXCEPTIONS = [
    ("com.mirth.connect.connectors.tcp.TcpDispatcher", "Error sending message via TCP.",
     "java.net.ConnectException: Connection refused (Connection refused)",
     ["java.base/java.net.PlainSocketImpl.socketConnect(Native Method)",
      "java.base/java.net.AbstractPlainSocketImpl.doConnect(AbstractPlainSocketImpl.java:412)",
      "java.base/java.net.Socket.connect(Socket.java:609)",
      "com.mirth.connect.connectors.tcp.TcpDispatcher.send(TcpDispatcher.java:284)",
      "com.mirth.connect.donkey.server.channel.DestinationConnector.handleSend(DestinationConnector.java:822)"],
     "java.net.ConnectException: Connection refused"),
    ("db-connector", "Error polling for messages.",
     "java.sql.SQLException: Cannot create PoolableConnectionFactory (Communications link failure)",
     ["org.apache.commons.dbcp2.BasicDataSource.createPoolableConnectionFactory(BasicDataSource.java:653)",
      "org.apache.commons.dbcp2.BasicDataSource.createDataSource(BasicDataSource.java:531)",
      "com.mirth.connect.connectors.jdbc.DatabaseReceiverQuery.poll(DatabaseReceiverQuery.java:205)",
      "com.mirth.connect.connectors.jdbc.DatabaseReceiver.poll(DatabaseReceiver.java:122)"],
     "com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure"),
    ("transformer", "Error evaluating transformer.",
     "com.mirth.connect.server.MirthJavascriptTransformerException: CHANNEL:\tADT Inbound\n"
     "CONNECTOR:\tsourceConnector\nSCRIPT SOURCE:\tTRANSFORMER\nSOURCE CODE:\n"
     "57: var mrn = msg['PID']['PID.3']['PID.3.1'].toString();\n"
     "58: var visit = lookup(mrn).visit.number;\nLINE NUMBER:\t58\n"
     "DETAILS:\tTypeError: Cannot read property \"visit\" from undefined",
     ["com.mirth.connect.server.transformers.JavaScriptFilterTransformer$FilterTransformerTask.doCall(JavaScriptFilterTransformer.java:183)",
      "com.mirth.connect.server.transformers.JavaScriptFilterTransformer$FilterTransformerTask.doCall(JavaScriptFilterTransformer.java:1)",
      "com.mirth.connect.server.util.javascript.JavaScriptTask.call(JavaScriptTask.java:113)",
      "java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)"],
     "org.mozilla.javascript.EcmaError: TypeError: Cannot read property \"visit\" from undefined (58#58)"),
]


PDF_BASE64 = "JVBERi0xLjQKJcfsj6IKNSAwIG9iago8PC9MZW5ndGggNiAwIFIvRmlsdGVyIC9GbGF0ZURlY29kZT4+CnN0cmVhbQp4nO"


def hl7(rng, n):
    return ("MSH|^~\\&|EPIC|HOSP|LAB|LAB|20261004093000||ADT^A01|MSG%06d|P|2.5.1\r"
            "EVN|A01|20261004093000\r"
            "PID|1||%07d^^^HOSP^MR||DOE^JANE^Q||19700101|F|||123 MAIN ST^^DENVER^CO^80202\r"
            "PV1|1|I|4W^401^A|||||||MED" % (n, rng.randrange(10 ** 7)))


def entries(rng, start, seq):
    """Yields (time, text) for one log entry at a time, forever."""
    now = start
    while True:
        now += datetime.timedelta(milliseconds=rng.randrange(5, 4000))
        seq += 1
        channel, cid = rng.choice(CHANNELS)
        thread = "Channel Dispatch Thread on %s (%s) < pool-1-thread-%d" % (channel, cid, rng.randrange(1, 9))
        roll = rng.random()
        if roll < 0.06:
            logger, summary, error, frames, cause = rng.choice(EXCEPTIONS)
            lines = ["ERROR %s [%s] %s: %s" % (stamp(now), thread, logger, summary), error]
            lines += ["\tat " + f for f in frames]
            lines += ["Caused by: " + cause] + ["\tat " + f for f in frames[:2]]
            lines.append("\t... %d more" % rng.randrange(20, 80))
            yield now, "\n".join(lines)
        elif roll < 0.12:
            yield now, "INFO  %s [%s] transformer: Inbound message: %s" % (stamp(now), thread, hl7(rng, seq))
        elif roll < 0.20:
            msg = rng.choice(WARN_MESSAGES).format(n=rng.randrange(2, 9000))
            yield now, "WARN  %s [%s] %s: %s" % (stamp(now), thread, rng.choice(LOGGERS), msg)
        else:
            msg = rng.choice(INFO_MESSAGES).format(n=seq)
            # Rare, as in a real log: a document logged whole, one long line.
            if rng.random() < 0.002:
                msg += " Payload (base64): " + (PDF_BASE64 * 200)[:rng.randrange(2000, 9000)]
            yield now, "INFO  %s [%s] %s: %s" % (stamp(now), thread, rng.choice(LOGGERS), msg)


def stamp(t):
    return t.strftime("%Y-%m-%d %H:%M:%S.") + "%03d" % (t.microsecond // 1000)


def fill(source, size, newline):
    """Takes entries until the text reaches size bytes; returns (bytes, time of the last entry)."""
    out, total, last = [], 0, None
    for when, text in source:
        line = text.replace("\n", newline) + newline
        data = line.encode("utf-8")
        out.append(data)
        total += len(data)
        last = when
        if total >= size:
            break
    return b"".join(out), last


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", required=True,
                        help="directory to write into; mirth.log and mirth.log.N.zip there are overwritten")
    parser.add_argument("--active-mb", type=float, default=2.0, help="size of mirth.log")
    parser.add_argument("--archives", type=int, default=5, help="number of mirth.log.N.zip archives")
    parser.add_argument("--archive-mb", type=float, default=1.0, help="uncompressed size of each archive")
    parser.add_argument("--crlf", action="store_true", help="end lines with CRLF, as an engine on Windows does")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    os.makedirs(args.dir, exist_ok=True)
    rng = random.Random(args.seed)
    newline = "\r\n" if args.crlf else "\n"
    source = entries(rng, datetime.datetime(2026, 10, 1, 8, 0, 0), 0)

    # Oldest first: archive 1 is the oldest, as log4j's default rollover numbers them.
    for index in range(1, args.archives + 1):
        data, last = fill(source, int(args.archive_mb * 1024 * 1024), newline)
        path = os.path.join(args.dir, "mirth.log.%d.zip" % index)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("mirth.log.%d" % index, data)
        os.utime(path, (last.timestamp(), last.timestamp()))
        print("wrote %s (%d bytes uncompressed)" % (path, len(data)))

    data, last = fill(source, int(args.active_mb * 1024 * 1024), newline)
    path = os.path.join(args.dir, "mirth.log")
    with open(path, "wb") as active:
        active.write(data)
    os.utime(path, (last.timestamp(), last.timestamp()))
    print("wrote %s (%d bytes)" % (path, len(data)))


if __name__ == "__main__":
    main()
