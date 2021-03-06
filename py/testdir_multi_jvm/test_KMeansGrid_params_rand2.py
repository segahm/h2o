import unittest
import random, sys, time
sys.path.extend(['.','..','py'])
import json

import h2o, h2o_cmd, h2o_hosts
import h2o_kmeans, h2o_import as h2i

def define_params(SEED):
    paramDict = {
        'k': [2, 5], # FIX! comma separated or range from:to:step
        'epsilon': [1e-6, 1e-2],
        # not used in Grid?
        # 'cols': [None, "0", "3", "0,1,2,3,4,5,6"],
        'max_iter': [None, 1, 5, 10], # FIX! comma separated or range from:to:step. Fail on 0?
        'seed': [None, 12345678, SEED],
        'normalize': [None, 0, 1],
        # 'destination_key:': "junk",
        
        }
    return paramDict

class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        global SEED, localhost
        SEED = h2o.setup_random_seed()
        localhost = h2o.decide_if_localhost()
        if (localhost):
            h2o.build_cloud(1,java_heap_GB=4)
        else:
            h2o_hosts.build_cloud_with_hosts()

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_KMeans_params_rand2(self):
        if localhost:
            csvFilenameList = [
                # ('covtype.data', 60),
                ('covtype.data', 800),
                ]
        else:
            csvFilenameList = [
                ('covtype.data', 800),
                ]

        importFolderPath = '/home/0xdiag/datasets/standard'
        h2i.setupImportFolder(None, importFolderPath)
        for csvFilename, timeoutSecs in csvFilenameList:
            # creates csvFilename.hex from file in importFolder dir 
            parseKey = h2i.parseImportFolderFile(None, csvFilename, importFolderPath,
                timeoutSecs=2000, pollTimeoutSecs=60)
            inspect = h2o_cmd.runInspect(None, parseKey['destination_key'])
            csvPathname = importFolderPath + "/" + csvFilename
            print "\n" + csvPathname, \
                "    num_rows:", "{:,}".format(inspect['num_rows']), \
                "    num_cols:", "{:,}".format(inspect['num_cols'])

            paramDict = define_params(SEED)
            for trial in range(3):
                # default
                params = {'k': 1 }
                # 'destination_key': csvFilename + "_" + str(trial) + '.hex'}

                h2o_kmeans.pickRandKMeansParams(paramDict, params)
                kwargs = params.copy()

                start = time.time()
                kmeans = h2o_cmd.runKMeansGridOnly(parseKey=parseKey, \
                    timeoutSecs=timeoutSecs, retryDelaySecs=2, pollTimeoutSecs=60, **kwargs)
                elapsed = time.time() - start
                print "kmeans grid end on ", csvPathname, 'took', elapsed, 'seconds.', \
                    "%d pct. of timeout" % ((elapsed/timeoutSecs) * 100)
                h2o_kmeans.simpleCheckKMeans(self, kmeans, **kwargs)

                ### print h2o.dump_json(kmeans)
                inspect = h2o_cmd.runInspect(None,key=kmeans['destination_key'])
                print h2o.dump_json(inspect)

                print "Trial #", trial, "completed\n"

if __name__ == '__main__':
    h2o.unit_main()
