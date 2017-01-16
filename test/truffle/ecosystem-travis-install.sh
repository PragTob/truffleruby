#!/usr/bin/env bash

set -e
set -x

unset GEM_HOME GEM_PATH

git clone \
    --branch master \
    https://github.com/jruby/jruby-truffle-gem-test-pack.git \
    ../jruby-truffle-gem-test-pack

test/truffle/gem-test-pack-checkout-revision.sh

