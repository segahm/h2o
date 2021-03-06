import h2o, h2o_cmd, h2o_jobs
import time, re, getpass

def setupImportS3(node=None, bucket='home-0xdiag-datasets', timeoutSecs=180):
    if not bucket: raise Exception('No S3 bucket specified')
    if not node: node = h2o.nodes[0]
    importS3Result = node.import_s3(bucket, timeoutSecs=timeoutSecs)
    # too many files now to print
    ### print h2o.dump_json(importS3Result)
    return importS3Result

# assumes you call setupImportS3 first
def parseImportS3File(node=None, 
    csvFilename='covtype.data', path='home-0xdiag-datasets', key2=None, 
    timeoutSecs=360, retryDelaySecs=2, initialDelaySecs=1, pollTimeoutSecs=180, noise=None,
    benchmarkLogging=None, noPoll=False, **kwargs):

    if not node: node = h2o.nodes[0]
    if not csvFilename: raise Exception('parseImportS3File: No csvFilename')
    s3Key= "s3://" + path + "/" + csvFilename

    # We like the short parse key2 name. 
    # We don't drop anything from csvFilename, unlike H2O default
    if key2 is None:
        # don't rely on h2o default key name
        myKey2 = csvFilename + '.hex'
    else:
        myKey2 = key2

    print "Waiting for the slow parse of the file:", csvFilename
    parseKey = node.parse(s3Key, myKey2, 
        timeoutSecs, retryDelaySecs, initialDelaySecs, pollTimeoutSecs, noise, 
        benchmarkLogging, noPoll, **kwargs)
    # a hack so we know what the source_key was, bask at the caller
    parseKey['source_key'] = s3Key
    print "\nParse result:", parseKey
    return parseKey

def setupImportFolder(node=None, path='/home/0xdiag/datasets', timeoutSecs=180):
    # a little hack to redirect import folder tests to an s3 folder
    # we don't have any "state" other than per node, so stuck this sort-of-global
    # test config state (which gets set only from the config json use-case)
    # on the nodes. The only globals we have are command line args..so lets keep that
    # Really should have a class for global H2O cloud state? or test state?
    if not node: node = h2o.nodes[0]
    if node.redirect_import_folder_to_s3_path: 
        # FIX! make bucket vary depending on path
        bucket = 'home-0xdiag-datasets'
        importFolderResult = setupImportS3(node=node, bucket=bucket, timeoutSecs=timeoutSecs)
    elif node.redirect_import_folder_to_s3n_path: 
        # FIX! make bucket vary depending on path
        path = re.sub('/home/0xdiag/datasets', '/home-0xdiag-datasets', path)
        importFolderResult = setupImportHdfs(node=node, path=path, schema="s3n", 
            timeoutSecs=timeoutSecs)
    else:
        if getpass.getuser()=='jenkins':
            print "Now: not doing Temp hack of /home/0xdiag/datasets/standard to /home/0xdiag/datasets"
            ## path = re.sub('/home/0xdiag/datasets/standard', '/home/0xdiag/datasets', path)
        importFolderResult = node.import_files(path, timeoutSecs=timeoutSecs)
    ### h2o.dump_json(importFolderResult)
    return importFolderResult

# assumes you call setupImportFolder first
def parseImportFolderFile(node=None, csvFilename=None, path=None, key2=None,
    timeoutSecs=30, retryDelaySecs=0.5, initialDelaySecs=1, pollTimeoutSecs=180, noise=None,
    benchmarkLogging=None, noPoll=False, **kwargs):
    if not node: node = h2o.nodes[0]

    if not csvFilename: raise Exception('parseImportFolderFile: No csvFilename')

    # We like the short parse key2 name. 
    # We don't drop anything from csvFilename, unlike H2O default
    if key2 is None:
        # don't rely on h2o default key name
        myKey2 = csvFilename + '.hex'
    else:
        myKey2 = key2

    print "Waiting for the slow parse of the file:", csvFilename

    # a little hack to redirect import folder tests to an s3 folder
    if node.redirect_import_folder_to_s3_path:
        # why no leading / for s3 key here. only one / after s3:/ ?
        path = re.sub('/home/0xdiag/datasets', 'home-0xdiag-datasets', path)
        parseKey = parseImportS3File(node, csvFilename, path, myKey2,
            timeoutSecs, retryDelaySecs, initialDelaySecs, pollTimeoutSecs, noise, 
            benchmarkLogging, noPoll)
    elif node.redirect_import_folder_to_s3n_path: 
        path = re.sub('/home/0xdiag/datasets', '/home-0xdiag-datasets', path)
        parseKey = parseImportHdfsFile(node, csvFilename, path, myKey2, "s3n",
            timeoutSecs, retryDelaySecs, initialDelaySecs, pollTimeoutSecs, noise, 
            benchmarkLogging, noPoll)
    else:
        if getpass.getuser()=='jenkins':
            print "Now: not doing Temp hack of /home/0xdiag/datasets/standard to /home/0xdiag/datasets"
            ### path = re.sub('/home/0xdiag/datasets/standard', '/home/0xdiag/datasets', path)
        importKey = "nfs:/" + path + "/" + csvFilename
        if h2o.beta_features:
            print "Temp hack to look at the jobs list for parse completion. No multiple outstanding parses"
            print "The parse result will be just from the first noPoll response. Parse is done as noPoll"

        parseKey = node.parse(importKey, myKey2, 
            timeoutSecs, retryDelaySecs, initialDelaySecs, pollTimeoutSecs, noise, 
            benchmarkLogging, noPoll=noPoll or h2o.beta_features, **kwargs)

        if h2o.beta_features:
            print "Temp hack to look at the jobs list for parse completion. No multiple outstanding parses"
            print "The parse result will be just from the first noPoll response."
            print "\nWaiting on Parse job for ", importKey
            start = time.time()
            h2o_jobs.pollWaitJobs(pattern='arse', timeoutSecs=timeoutSecs, pollTimeoutSecs=120, retryDelaySecs=5)
            print "Parse job end for ", importKey, 'took', time.time() - start, 'seconds'

         # a hack so we know what the source_key was, bask at the caller
        parseKey['python_source_key'] = importKey
        print "\nParse result:", parseKey
    return parseKey

def setupImportHdfs(node=None, path=None, schema='hdfs', timeoutSecs=180):
    if not node: node = h2o.nodes[0]

    print "setupImportHdfs schema:", schema
    # FIX! H2O has horrible inconsistencies between the URIs used for different filesystems
    if schema == "maprfs":
        hdfsPrefix = schema + "://"
    elif schema == "s3n":
        hdfsPrefix = schema + ":/"
    elif schema == "hdfs":
        hdfsPrefix = schema + "://" + node.hdfs_name_node
    else: 
        raise Exception('Uknown schema: ' + schema + ' in setupImportHdfs')

    if path is None:
        URI = hdfsPrefix + '/datasets'
    else:
        URI = hdfsPrefix + path

    print "URI:", URI
    importHdfsResult = node.import_hdfs(URI, timeoutSecs=timeoutSecs)
    h2o.verboseprint(h2o.dump_json(importHdfsResult))
    return importHdfsResult

def parseImportHdfsFile(node=None, csvFilename=None, path='/datasets', key2=None, schema='hdfs',
    timeoutSecs=3600, retryDelaySecs=2, initialDelaySecs=1, pollTimeoutSecs=180, noise=None,
    benchmarkLogging=None, noPoll=False, **kwargs):
    if not csvFilename: raise Exception('No csvFilename parameter in parseImportHdfsFile')
    if not node: node = h2o.nodes[0]

    print "parseImportHdfsFile schema:", schema
    if schema == "maprfs":
        hdfsPrefix = schema + ":" # no ?? ? inconsistent with import
    elif schema == "s3n":
        hdfsPrefix = schema + ":/"
    elif schema == "hdfs":
        hdfsPrefix = schema + "://" + node.hdfs_name_node
    else: 
        raise Exception('Uknown schema: ' + schema + ' in parseImportHdfsFile')

    if path is None:
        URI = hdfsPrefix + '/datasets'
    else:
        URI = hdfsPrefix + path

    hdfsKey = URI + "/" + csvFilename
    print "parseImportHdfsFile hdfsKey:", hdfsKey

    ## This fails for pattern matching. parse works, but not inspect?
    ## inspect = h2o_cmd.runInspect(key=hdfsKey, timeoutSecs=180)
    ## print "parseImportHdfsFile inspect of source:", inspect

    if key2 is None:
        myKey2 = csvFilename + ".hex"
    else: 
        myKey2 = key2

    parseKey = node.parse(hdfsKey, myKey2,
        timeoutSecs, retryDelaySecs, initialDelaySecs, pollTimeoutSecs, noise, 
        benchmarkLogging, noPoll, **kwargs)
    # a hack so we know what the source_key was, bask at the caller
    parseKey['source_key'] = hdfsKey
    print "parseImportHdfsFile:", parseKey
    return parseKey
