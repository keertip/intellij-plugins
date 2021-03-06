var join = require('path').join;
var argv = process.argv;
var RUNNER_PORT = 'runnerPort';
var CONFIG_FILE = 'configFile';
var KARMA_PACKAGE_DIR = 'karmaPackageDir';

function parseArguments() {
  var options = {};
  for (var i = 2; i < argv.length; i++) {
    var arg = argv[i];
    if (arg.indexOf('--') !== 0) {
      throw new Error('Illegal prefix ' + arg);
    }
    var ind = arg.indexOf('=');
    if (ind === -1) {
      throw new Error('No "=" symbol found in ' + arg);
    }
    var key = arg.substring(2, ind);
    options[key] = arg.substring(ind + 1);
  }
  return options;
}

var options = parseArguments();

function getKarmaPackageDir() {
  var karmaPackageDir = options[KARMA_PACKAGE_DIR];
  if (!karmaPackageDir) {
    throw Error("Karma package dir isn't specified.");
  }
  return karmaPackageDir;
}

exports.requireKarmaModule = function(moduleName) {
  var karmaPath = getKarmaPackageDir();
  return require(join(karmaPath, moduleName));
};

exports.getConfigFile = function() {
  var configFile = options[CONFIG_FILE];
  if (!configFile) {
    throw Error("Config file isn't specified.");
  }
  return configFile;
};

exports.getRunnerPort = function() {
  var runnerPortStr = options[RUNNER_PORT] || '';
  var runnerPort = parseInt(runnerPortStr, 10);
  if (isNaN(runnerPort)) {
    return undefined;
  }
  return runnerPort;
};
