# Transaction-Manager

## Setup

This project uses [Git Submodules](https://git-scm.com/book/en/v2/Git-Tools-Submodules).
When cloning this project, you need to run:

```sh
user@pc $git submodule init
user@pc $git submodule update
```

To pull updates from this repository and its submodules, run:

```sh
user@pc $git pull --recurse-submodules
```

To make updates to a submodule, run:

```sh
user@pc submodule/ $git push --recurse-submodules=on-demand
```
