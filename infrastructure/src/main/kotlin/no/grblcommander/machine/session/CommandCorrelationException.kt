package no.grblcommander.machine.session

import java.io.IOException

/** A line was interrupted or cannot be admitted without risking response miscorrelation. */
class CommandCorrelationException(message: String) : IOException(message)
