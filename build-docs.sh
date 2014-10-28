#!/bin/sh
lein doc
(cd doc; make)
cp -vr doc/index.html /tmp/index.html;
mv -v doc/api /tmp/api
git checkout gh-pages;

rm -rf static index.html api
mv -fv /tmp/index.html .
mv -fv /tmp/api .

git add --all index.html
git add --all api

git commit -a -m "Update doc"
