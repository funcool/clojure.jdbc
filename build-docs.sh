#!/bin/sh
lein marg
lein doc
cp docs/uberdoc.html /tmp/uberdoc.html
cp -r docs/codox /tmp/codox
git checkout gh-pages;

rm -rf api
mv /tmp/uberdoc.html index.html
mv /tmp/codox api
git add --all index.html
git add --all api
git commit -m "Update doc"
