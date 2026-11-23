#!/bin/sh
# Regenerate the pad's Faust effects (native/fx/generated) from native/fx/faust/*.dsp. Needs faust (2.70 here).
set -e
cd "$(dirname "$0")/../native/fx/faust"
for f in *.dsp; do
    n=$(basename "$f" .dsp)
    c="$(echo "$n" | cut -c1 | tr a-z A-Z)$(echo "$n" | cut -c2-)Dsp"
    faust -lang cpp -cn "$c" -scn FaustDsp -nvi -i "$f" -o "../generated/$n.h"
    echo "$n -> $c"
done
