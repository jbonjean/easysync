# Eclipse EasySync

Very basic Eclipse plugin to sync preferences between instances.

 * It is not battle tested and could contain some major bugs.
 * Only tested on Linux.
 * You should probably use the Preference Recorder instead.

## How to install?

Eclipse update site: https://raw.githubusercontent.com/jbonjean/eclipse-update-site/master/easysync

## Why?

Mostly because the Preference Recorder needs an Eclipse restart to synchronize
preferences between two Eclipse instances.

## What does it do?

The plugin simply dumps (on demand) all preferences to a single file. You can
then load it in another instance, use file synchronization solutions to share
it (Seafile for example), ...

Once installed, you will have a new Sync menu, that allows you to Save or Load
your preferences.

## Advanced

### Where are the files stored?

The files are stored in `$HOME/.config/eclipse-easysync`:
 * `preferences.db`: preferences storage.
 * `preferences.config`: configuration file, described in next section.

There is also a file `/tmp/preferences.excluded.db` generated on save that
could be useful for debugging or fine tuning the configuration.

### How to configure?

You don't need any configuration for the plugin to work, but the configuration
file allows you to fine tune which preferences are going to be synchronized.

Before editing the file, you should trigger a first synchronization so the
configuration file is generated with the proper sections.

Each section (Eclipse preferences node) contains two empty keys that you can
customize:
 * `exclude.list`: comma separated list of key that will be excluded during
   synchronization
 * `exclude.pattern`: regex that will be applied to determine is a key can be
   synchronized

Both methods serve the same purpose and can be used together.

## Development

* Create a local Eclipse update site:
```
mvn clean package
```
The repository will be created at `site/target/repository/`.

## License

EasySync is licensed under the Apache License, Version 2.0. See LICENSE for the full license text.

