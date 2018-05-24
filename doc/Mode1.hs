{-# LANGUAGE DataKinds #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE MonoLocalBinds #-}

{-# OPTIONS_GHC -Wno-name-shadowing #-}

module Mode1 where

import Concerto

program :: Inst ()
program = do
    in_re <- await
    in_im <- await
    in_direction <- await
    in_th <- await
    ss_signal <- await

    in_re <- load "basic" in_re
    in_im <- load "basic" in_im   
    in_direction <- load "basic" in_direction
    in_th <- load "basic" in_th
    ss_signal <- load "basic" ss_signal
    bit42 <- task1 "SpectrumSensing" in_re in_im in_direction in_th ss_signal
    bit42 <- store "basic" bit42

    bit42 <- load "basic" bit42
    start <- load "basic" start
    stop <- load "basic" stop
    [ss_signal, jamming_signal] <- task2 "Mode1SM" bit42 start stop
    ss_signal <- store "basic" ss_signal
    jamming_signal <- store "basic" jamming_signal

    jamming_signal <- load "basic" jamming_signal
    jamming_out <- task3 "Jamming" jamming_signal
    jamming_out <- store "basic" jamming_out