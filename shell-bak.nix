{ pkgs ? import <nixpkgs> {} }:

with pkgs;

mkShell {
  buildInputs = [
    clojure

    openjdk17
    maven

    babashka
    clj-kondo
    clojure-lsp
    jet

    google-chrome
    nodejs
    playwright-driver

    openai-whisper-cpp
    ffmpeg
  ];
}
