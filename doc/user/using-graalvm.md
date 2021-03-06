# Using TruffleRuby with GraalVM

RubyTruffle is designed to be run with a JVM that has the Graal compiler.

The easiest way to get a JVM with Graal is via the GraalVM, available from the
Oracle Technology Network. This includes the JVM, the Graal compiler, and
TruffleRuby, all in one package with compatible versions. You can get either the
runtime environment (RE) or development kit (DK).

http://www.oracle.com/technetwork/oracle-labs/program-languages/

Inside the GraalVM is a `bin/ruby` command that runs TruffleRuby.

You can also use GraalVM to run a different version of TruffleRuby than the one
it packages, but this not advised for end-users.
