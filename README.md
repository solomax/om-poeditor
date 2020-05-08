# om-poeditor

## Prerequisites
 1. get API token and put it to file (for ex. `~/.token`)
 2. run `mvn clean package`

## Get the list of languages and codes from POEditor
`mvn exec:java -Dexec.args="~/.token ${OM_SOURCES}/ list"`

## Get translations from POEditor
`mvn exec:java -Dexec.args="~/.token ${OM_SOURCES}/"`

## Upload languages files to POEditor
`mvn exec:java -Dexec.args="~/.token ${OM_SOURCES}/ put"`

<span style="color: red;"><strong>NOTE: all translation will be overwritten, it will take significant time</strong></span>
