# MongoDB slow operation profiler and visualizer

This java web application collects slow operations from a mongoDB system in order to visualize and analyze them.
Since v2.0.0 it may be easily extended to an administration tool by implementing commands to be executed against the configured database system(s).
The initial version of the software has been presented during the [MongoDB User Group Berlin on 4th of June 2013](http://www.meetup.com/MUGBerlin/events/119503502/).
Slides of the presentation can be found [here](http://www.slideshare.net/Kay1A/slow-ops).

The following first screenshot demonstrates how slow operations are visualized in the diagram: The higher a point or circle on the y-axis, the slower was the execution time of this operation. The greater the diameter of the circle, the more slow operations of this type were executed at this time. 

You can zoom-in by drawing a rectangle with the mouse around the area you are interested in. I suggest to zoom-in **first** horizontally, and then zoom-in vertically. Press Shift + drag mouse in order to move the visible area around. Double click returns to the initial viewport.

While the mouse hovers over the diagram, the corresponding slow operations are shown in bold and details of them are displayed on the right-hand side legend. The different colors in the legend are just to better distinguish the different entries and have no further meaning.

The more "Group by" checkboxes are checked, the more details constitute slow operation types, thus the more details you'll see for each slow operation type.


### Example

For example, the following screenshot shows that only a few databases have been **filtered**, defined by their labels, server address, replica set name, database and collection for a specific time period of one day. 
Since they are **grouped by** their label, operation, queried and sorted fields, the legend below on the right shows these 4 details grouped together for the time period which is being hovered over by the mouse, here at 04:00 o'clock. As the the **resolution** is set to `Hour`, all slow operations occurred during within the hour hovered over by the mouse, here from 04:00 until 04:59.59 o'clock, are shown.

In this example, during 1 hour from 04:00 o'clock occurred 4 different slow operation types, two on `offerstore-it` and two on `offerstore-en`. As the legend is sorted by y-axis, thus duration, the slowest operation type is shown first. 

The slowest operation type happened on `offerstore-it` by executing a `count` command on both fields `missingSince`, using an `$ne` expression, and field `shopId`, using a concrete value. This slow operation type occurred 9 times, its minimum duration was 3,583 ms, its maximum 40,784 ms and its average 11,880.56 ms. 

The second slowest operation type happened on `offerstore-en` by fetching a next batch (op=`getmore`). The queried fields are unknown for a getmore operation but the query itself, including its used fields, may have been recorded earlier already because there is no reason that the query itself has been faster than its followed getmore operation.

The third slowest operation type happened on `offerstore-it` by executing a `count` command on both fields `shopId`, using a concrete value, and on field `bokey` using a range query with both operators `$lte` and `$gte`.

The fourth and last slowest operation type in this example from 04:00 until 04:59 o'clock happened on `offerstore-en` by executing a query on both fields `exportIFP.IDEALO`, using an `$exists` operator and on field `shopId`, using a concrete value.


## Example screenshot of the analysis page


![Screenshot](img/slow_operations_gui_diagram.jpg "Screenshot of the analysis page")

### Summarized table

Since v1.0.3 the user analysis page, besides the diagram, has also a table of all selected slow operation types. The table is filterable. Columns are sortable and selectable to be hidden or shown. The sum over the values of columns, where it makes sense, is shown at the bottom of the columns.

For example, to see the most expensive slow operations first, just sort descending by column `Sum ms`.

Here is a reduced screenshot of the table where all columns are shown, sorted by column `Max ms`, without any filter on rows.

![Screenshot](img/slow_operations_gui_table.jpg "Screenshot of the table")


## Applicaton status page

Since v1.0.3 there is also a page to show the application status. Besides showing the status of the collector, means where and how many slow operations have been collected (read and written) since application restart, it shows also every registered database in a table. Since profiling works per database, each database to be profiled is in one row.

The table is filterable. Columns are sortable and selectable to be hidden or shown. The sum over the values of columns, where it makes sense, is shown at the bottom of the columns. The table is by default sorted by the columns `Label`, `ReplSet` and `Status` which gives a very good overview over a whole bunch of clusters. **Hint:** Hold shift key pressed while clicking the column headers in order to sort multiple columns.

Here is a reduced screenshot of some first rows of the table, ordered by columns `ReplSet` and `Status`, with a filter "datastore" applied on rows:

![Screenshot](img/slow_operations_app_status.png "Screenshot of the application status page")

At its right side, the table has a bunch of time slot columns (10 sec, 1 min, 10 min, 30 min, 1 hour, 12 hours, 1 day). These show the number of slow operations collected during these last time periods, so you can see already here which databases may behave abnormally. In such case, you may either analyse those databases or switching off collecting  or lower their `slowMs` threshold in order to profile less slow operations.

#### Actions

Since v2.0.2, a floating **Actions panel** is shown always on top and can be switched on or off. Both `refresh` and `analyse` actions were implemented already before v2.0.0. `refresh` gets and shows the latest data of the selected database(s). `analyse` opens the above mentionned analysis page to show the slow operation types of the last 24 hours of the selected node(s) respectively database(s). Both `collecting start/stop` and `set slowMs` were also already implemented before but since v2.0.0 they are only shown to authorized users. "Authorized users" are users who used the url parameter `adminToken` set to the right value (see below under "configuration" for more details).

Since v2.0.0. you may execute **commands** against the selected database system(s). Since v2.0.3 you can choose whether the command has to run against the corresponding database system (i.e. mongos-router) or against the individually selected nodes (i.e. mongod). The difference is that the command will run either against the entry point of the database system (i.e. router or primary) or against all selected nodes wich may be secondaries as well. Current implemented commands are:

+ list databases and their collections
+ show currently running operations (requires mongodb v3.2 or newer)
+ show index access statistics of all databases and their collections (requires mongodb v3.2 or newer)

The command result is shown in a new page in a filterable table. Columns are sortable as well, so you can detect immediately spikes. **Hint:** Hold shift key pressed while clicking the column headers in order to sort multiple columns.

Here is a cutout of a screenshot showing the result of the current-operation command. The table is sorted by column "secs running" in order to see slow operations first.

![Screenshot](img/slow_operations_command_result_page.png "Screenshot of the operation command result page")

Implementing new commands is quite easy: just create a new java class which implements the interface `de.idealo.mongodb.slowops.command.ICommand`. The interface has only 2 methods in order to execute the database command and to transform the result to a corresponding table structure.

This being said, from v2.0.0 on, the webapp may be extended from a pure monitoring and analyzing tool to an administration tool.

#### Dynamic configurations

Since v1.2.0, authorized users may dynamically upload new configurations in order to add, remove or change databases to be registered respectively to be profiled. The configuration of the collector writer may also be changed. "Authorized users" are users, who used the url parameter `adminToken` set to the right value (see [Configuration](#config) below for more details).
The uploaded config is **not** persisted server side and will be lost upon webapp restart. All servers of changed "profiled"-entries are (re)started. Also the collector needs to be restarted if its config changed. Even though stops and starts are executed simultaneously, it may take some time depending on how many changes need to be applied, thus how many readers, respectively the writer, are involved by the config change.


##   Setup

### Preconditions

1. java 1.8 or newer
2. maven 2.0 or newer
3. mongoDB 2.0 or newer

### Starting up

1. Clone the project:   
`git clone https://github.com/idealo/mongodb-slow-operations-profiler.git`
2. Enter the server addresses, database and collection names in file "`mongodb-slow-operations-profiler/src/main/resources/config.json`" (see [Configuration](#config) below)
3. While being in the in the project folder "`mongodb-slow-operations-profiler/`", build a war file by executing in a shell:  
`mvn package`
4. Deploy the resulted war file (e.g. "`mongodb-slow-operations-profiler-1.0.3.war`") on a java webserver (e.g. tomcat). Dependent on the above mentionned `config.json`, it may automatically start collecting slow operations. If no slow operations exist yet on the mongod's, the collector(s) will sleep 1 hour before retrying.
5. The application can be accessed through a web browser by the URL `http://your-server:your-port/mongodb-slow-operations-profiler-[your-version]/app`
6. To visualize and analyze slow operations either select one or more entries and click "analyse" or use the following URL `http://your-server:your-port/mongodb-slow-operations-profiler-[your-version]/gui`

### <a name="config"></a> Configuration

The application is configured by the file "`mongodb-slow-operations-profiler/src/main/resources/config.json`". It's a json formatted file and looks like this:

```json
{
  "collector":{
    "hosts":["myCollectorHost_member1:27017",
             "myCollectorHost_member2:27017",
             "myCollectorHost_member3:27017"],
    "db":"profiling",
    "collection":"slowops",
    "adminUser":"",
    "adminPw":""
  },
  "profiled":[
    { "enabled": false,
      "label":"dbs foo",
      "hosts":["someHost1:27017",
               "someHost2:27017",
               "someHost3:27017"],
      "ns":["someDatabase.someCollection", "anotherDatabase.anotherCollection"],
      "adminUser":"",
      "adminPw":"",
      "slowMS":250
    },
    { "enabled": false,
      "label":"dbs bar",
      "hosts":["someMongoRouter:27017"],
      "ns":["someDatabase.someCollection", "anotherDatabase.*"],
      "adminUser":"",
      "adminPw":"",
      "slowMS":250
    }
  ],
  "yAxisScale":"milliseconds",
  "adminToken":"mySecureAdminToken"
}
```
This example configuration defines first the `collector` running as a replica set consisting of 3 members on hosts "myCollectorHost_member[1|2|3]" on port 27017, using the collection "slowops" of database "profiling". Both `adminUser` and `adminPw` are empty because the mongodb instance runs without authentication. If mongod runs with authentication, the user must exist for the admin database with role "root".

After the definition of the collector follow the databases to be profiled. In this example, there are only two entries. However, keep in mind that the application will **resolve all members** of a replica set (even if only 1 member has been defined) respectively all shards and its replica set members of a whole mongodb cluster.

Fields of `profiled` entries explained:

* `enabled` = whether collecting has to be started automatically upon (re)start of the application
* `label` = a label of the database system in order to be able to filter, sort and group on it
* `hosts` = an array of members of the same replica set, or just a single host, or a mongo router
* `ns` = an array of the namespaces to be collected in the format of `databaseName.collectionName`. The placeholder `*` may be used instead of `collectionName` to collect from all collections of the given database.
* `adminUser`= if authentication is enabled, name of the user for database "admin" having role "root"
* `adminPw`= if authentication is enabled, passwort of the user 
* `slowMS`= threshold of slow operations in milliseconds

The field `yAxisScale` is to be set either to the value "milliseconds" or "seconds". It defines the scale of the y-axis in the diagram of the analysis page.

In v2.0.0 the field `adminToken` has been introduced to restrict access to administrative functionalities i.e. stop/start of collecting slow operations, setting the threshold `slowMs`, seeing the currently used configuration or uploading a new configuration.
To grant access to these functionalities, add the parameter `adminToken=` followed by your configured value, i.e. `mySecureAdminToken`, to the URL of the application status page, i.e. `http://your-server:your-port/mongodb-slow-operations-profiler-[your-version]/app?adminToken=mySecureAdminToken`.


## Version history

* v2.2.1
   + bugfix: the initial viewport of the diagram did not always cover the whole x-axis range
* v2.2.0
   + new: the diagram of the analysis page has now new options to redraw the y-axis either as avg, min, max or sum of the duration of the slow operation types, to easily spot spikes
   + update: localized formatting of numbers in the legend of the diagram and in the summarized table of the analysis page
   + update: option "exclude 14-days-operations" removed because newer versions of mongodb have fixed this
   + bugfix: labels of slow operations types in the legend of the diagram were mixed-up since last version v2.1.1
   + bugfix: the legend of the chronologically very first slow operation types was not shown next to the diagram
* v2.1.1
   + bugfix: the diagram displayed superfluously also the accumulation of all distinct slow operation types, resulting in big circles at the first occurrence of each distinct slow operation type, thus often shown at the very left on the x-axis
   + bugfix: after refreshing the application status page, the status of profiling, collecting and slowMs of nodes whose status changed within the cache time of 1 minute might have been wrongly reported because these values were not immediately updated in the cache
* v2.1.0
   + new: application status page loads much quicker if many mongod's or databases are registered because the status of mongod's and databases are now cached; a page reload will refresh the status of mongod's and databases in the background if it's older than 1 minute
* v2.0.3
   + new: option to run command against database system or against selected nodes
* v2.0.2
   + new: action panel is now floating on top so it's always visible which avoids scrolling down the whole page to use it
* v2.0.1
   + new: preset search filter on application status page with value of url parameter `lbl`
* v2.0.0
   + new: show tabular result of commands executed against the selected database system(s); implemented commands are:
     + show current running operations
     + list databases and their collections
     + show index access statistics of all databases and their collections (requires mongodb v3.2 or newer)
   + new: grant access to administrative functionalities only to authorized users
   + update: use mongodb-java-driver v3.4.2 instead of previously used v3.3.0
   + update: for consistent reads `ReadPreference.primaryPreferred()` is used instead of previously used `ReadPreference.secondaryPreferred()` - except for analysing collected slow operations which still uses `ReadPreference.secondaryPreferred()`
* v1.2.1
    + bugfix: removing profiling reader(s) when uploading a new config might have failed
    + update: both parameters fromDate and toDate are required on analyse page and will be set to default if not existent; other given parameters are applied independently
    + update: change index from {adr:1, db:1} to {adr:1, db:1, ts:-1} on profiling.slowops collection to accelerate start-up of profiling readers when they query for their newest written entry in order to continue from
    + update: change index from {ts:-1} to {ts:-1, lbl:1} on profiling.slowops collection to speed-up analyse queries having also "lbl" ("ts" is always provided)
* v1.2.0
    + new: show currently used configuration as json
    + new: upload new configuration as json (which is applied but not persisted server side); all servers of changed "profiled"-entries are (re)started; collector is restarted if its config changed
    + new: option to refresh only collector status
    + new: show number of reads and writes from current and removed/changed profilers and the collector which helps to see if all reads of the profilers got written by collector
    + update: socket timeout for analyze queries set from 10 to 60 seconds
* v1.1.2
    + bugfix: the sum on columns was not updated when rows were filtered on analysis page
    + new: column `ms/ret` in table on analysis page added which shows how much time was globally spent for one returned document
* v1.1.1
    + new: when using placeholder `*` to collect from all collections, exclude documents of namespace `db.system.profile` because reading from `db.system.profile` may be slower than defined by slowMS, resulting in a new entry in `db.system.profile` which is irrelevant for the analysis
* v1.1.0
    + new: namespace (`profiled.ns`) in config.json may use placeholder `*` for collection names (i.e. `mydb.*`) in order to collect from all collections of the given database
* v1.0.3
    + new: multiple databases and collections for different replica sets, clusters or single mongod's can be defined to be profiled
    + new: automatic resolving of all mongod's constituting the defined clusters and replica sets
    + new: overview of all resolved mongod's and their state i.e. primary or secondary, databases, being profiled or not, number of profiled slow operations per database in total and in the last time periods of 10 seconds, 1, 10, 30 minutes, 1, 12 and 24 hours
    + new: option to set `slowMs` threshold, to start or to stop profiling and collecting of slow operations for multiple selected databases with one click
    + new: just tick one or multiple databases to open the diagram showing slow operations graphically for the selected databases, mongod's, replica sets or whole clusters
    + new: besides the diagram showing slow operation graphically, a filterable and sortable table displays different metrics of these slow operations, so you can easily spot the most expensive operations within the chosen time period
    + new: option to show circles in the diagram as square root of count-value to reduce the diameter of the circles which is useful when there are too many slow operations of the same type resulting in circles which are too large to fit in the diagram
    + new: the fingerprint of a query is better distinguished i.e. a query like `{_id:{$in:[1,2,3]}}` was formerly fingerprinted just as `_id` but now its `_id.$in` which is helpful to spot queries using operators which may cause performance issues
    + new: better identification of `command` i.e. `command` can be `count`, `findAndModify`, `distinct`, `collStats`, `aggregate` etc. which was formerly not itemized, so instead of seeing just `command` you see now `command.count` for example
    + new: better itemizing of aggregations, i.e. two pipelines `match` and `group` may be itemized as for example: `[$match._id, $group.city]` which makes identifying different aggregations easier
    + new: using java 1.8
* v0.1.1
    + new: sort legend by count or y-value
    + update: Eclipse's Dynamic Web Module facet changed from v2.5 to v3.0 (changes web-app tag in web.xml)
* v0.1.0
    + new: filter by date/time
    + new: filter by millis
    + change: deprecated tags removed from logback configuration
    + bugfix: respect time zone and day light saving time (data are still saved in GMT but now displayed dependent on time zone and day light saving time)
    + bugfix: zoom into graph could have resulted in a blank graph due to conflict with the previous date picker javascript library
    + bugfix: date formatting is now thread safe
* v0.0.2
    + logback configuration file
    + maven-war plugin update
* v0.0.1
    + initial release


## Third party libraries

* mongo-java-driver: [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
* slf4j: [MIT License](http://opensource.org/licenses/MIT)
* logback: [LGPL 2.1](http://www.gnu.org/licenses/old-licenses/lgpl-2.1)
* google-collections (Guava): [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
* jongo: [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
* jackson: [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
* bson4jackson: [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
* dygraph: [MIT License](http://opensource.org/licenses/MIT)
* bootstrap-datetimepicker: [Apache License 2.0](https://github.com/tarruda/bootstrap-datetimepicker)


## License

This software is licensed under [AGPL 3.0](http://www.gnu.org/licenses/agpl-3.0.html).
For details about the license, please see file "LICENSE", located in the same folder as this "README.md" file.



