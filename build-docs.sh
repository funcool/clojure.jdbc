#!/bin/sh
lein marg
cp docs/uberdoc.html /tmp/uberdoc.html
git checkout gh-pages;

mv /tmp/uberdoc.html index.html
git add index.html
git commit index.html -m "Update doc"
