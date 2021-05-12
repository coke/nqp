# Overview

Create a .NET 5 backend to NQP.

# Plan

* Use MoarVM backend to generate .NET source implementation
* Compile DLLs from generated source for stage0 build
* Remove MoarVM integration, run against stage0

As progress is made:

* pass nqp tests
* build rakudo
* pass rakudo tests
* pass rakudo spec tests

# Specifics

* Update nqp-configure to accept dotnet as a backend

