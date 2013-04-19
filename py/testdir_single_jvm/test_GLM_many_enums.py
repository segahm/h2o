import unittest
import random, sys, time, os
import string
import re
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd, h2o_hosts, h2o_browse as h2b, h2o_import as h2i, h2o_glm

# we allow $ prefix and % suffix as decorators to numbers?
whitespaceRegex = re.compile(r"""
    ^\s*$     # begin, white space or empty space, end
    """, re.VERBOSE)

specialRegex = re.compile(r"""
    ^\s*     # begin, white space or empty space
    [%\$+-]* # single chars that might be considered numbers
    \s*$    # white space or empty space, end
    """, re.VERBOSE)

number1Regex = re.compile(r"""
    ^\s*     # begin, white space or empty space
    [\$\%]?  # number can have dollar sign or percent to start?
    [+-]?    # plus or minus. maybe h2o matches multiple?
    ([0-9]*\.[0-9]*)?  # decimal point focused. optional whole and fractional digits. h2o thinks whole thing optional
    ([eE][-+]?[0-9]*)? # optional exponent. A single e matches (incorrectly)
    (\s*\%*)? # can have zero or more percent. Percent can have a space?
    \s*$     # white space or empty space, end
    """, re.VERBOSE)

# this matches white space? makes all white space count as number?
number2Regex = re.compile(r"""
    ^\s*     # begin, white space or empty space
    [\$\%]?  # number can have dollar sign or percent to start?
    [+-]?    # plus or minus. maybe h2o matches multiple?
    ([0-9]+)? # one or more digits. h2o thinks whole thing optional
    (\.[0-9]*)? # optional decimal point and fractional digits
    ([eE][-+]?[0-9]*)? # optional exponent. a single e matches (incorrectly)
    (\s*\%*)? # can have zero or more percent. Percent can have a space?
    \s*$     # white space or empty space, end
    """, re.VERBOSE)

# can nans have the +-%$ decorators?. allow any case?
nanRegex = re.compile(r"""
    ^\s*     # begin, white space or empty space
    [\$\%]?  # number can have dollar sign or percent to start?
    [+-]?    # plus or minus
    [Nn][Aa][Nn]? # nan or na
    (\s*\%*)? # can have zero or more percent. Percent can have a space?
    \s*$     # white space or empty space, end
    """, re.VERBOSE)

# we want to seed a random dictionary for our enums
# string.ascii_uppercase string.printable string.letters string.digits string.punctuation string.whitespace
# can use comma when hive 01 is used

# Apparently we don't have any new EOL separators for hive?
# we allow extra chars in the hive separated columns..i.e. single and double quote.

# seems to have a problem with %? where does H2O allow it?
# % $ +- ?
# def random_enum(maxEnumSize, randChars=string.letters + "-.;|\t ", quoteChars="\'\""):

# I don't understand all the patterns with % that are allowed for numbers..take it out
def random_enum(maxEnumSize, randChars="aeE01" + "$+-.;|\t ", quoteChars="\'\""):
    choiceStr = randChars + quoteChars
    mightBeNumber = True
    while mightBeNumber:
        # H2O doesn't seem to tolerate random single or double quote in the first two rows.
        # disallow that by not passing quoteChars for the first two rows
        r = ''.join(random.choice(choiceStr) for x in range(maxEnumSize))

        mightBeNumber = False
        if specialRegex.match(r): # all whitespace
            # print "regenerate due to WARNING: generated enum is all white space: '" + r + "'"
            ### for i in whitespaceRegex.findall(r): print i
            mightBeNumber = True
        if number1Regex.match(r):
            mightBeNumber = True
        if number2Regex.match(r):
            mightBeNumber = True
        if nanRegex.match(r):
            mightBeNumber = True

    return r

# MAX_ENUM_SIZE in Enum.java is set to 11000 now
def create_enum_list(maxEnumSize=8, listSize=11000, **kwargs):
    # allowing length one, we sometimes form single digit numbers that cause the whole column to NA
    # see DparseTask.java for this effect
    # FIX! if we allow 0, then we allow NA?. I guess we check for no missing, so can't allow NA
    # too many retries allowing 1. try 2 min.
    # enumList = [random_enum(random.randint(2,maxEnumSize), **kwargs) for i in range(listSize)]
    enumList = [random_enum(4, **kwargs) for i in range(listSize)]
    return enumList

def write_syn_dataset(csvPathname, rowCount, colCount=1, SEED='12345678', 
        colSepChar=",", rowSepChar="\n", quoteChars=""):
    r1 = random.Random(SEED)
    enumList = create_enum_list(quoteChars=quoteChars)

    dsf = open(csvPathname, "w+")
    for row in range(rowCount):
        # doesn't guarantee that 10000 rows have 10000 unique enums in a column
        # essentially sampling with replacement
        rowData = []
        for col in range(colCount):
            ri = random.choice(enumList)
            # first two rows can't tolerate single/double quote randomly
            # keep trying until you get one with no single or double quote in the line
            if row < 2:
                while True:
                    # can't have solely white space cols either in the first two rows
                    if "'" in ri or '"' in ri or whitespaceRegex.match(ri):
                        ri = random.choice(enumList)
                    else:
                        break

            rowData.append(ri)

        # output column
        ri = r1.randint(0,1)
        rowData.append(ri)

        # use the new Hive separator
        rowDataCsv = colSepChar.join(map(str,rowData)) + rowSepChar
        ### sys.stdout.write(rowDataCsv)
        dsf.write(rowDataCsv)
    dsf.close()

class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        global SEED
        SEED = random.randint(0, sys.maxint)
        # SEED = 
        random.seed(SEED)
        print "\nUsing random seed:", SEED
        global localhost
        localhost = h2o.decide_if_localhost()
        if (localhost):
            h2o.build_cloud(1,java_heap_GB=1)
        else:
            h2o_hosts.build_cloud_with_hosts()

    @classmethod
    def tearDownClass(cls):
        ### time.sleep(3600)
        h2o.tear_down_cloud()

    def test_GLM_many_enums(self):
        SYNDATASETS_DIR = h2o.make_syn_dir()

        if not localhost:
            n = 200
            tryList = [
                (n, 1, 'cD', 300), 
                (n, 2, 'cE', 300), 
                (n, 3, 'cF', 300), 
                (n, 4, 'cG', 300), 
                (n, 5, 'cH', 300), 
                (n, 6, 'cI', 300), 
                ]
        else:
            n = 200
            tryList = [
                (n, 1, 'cD', 300), 
                (n, 2, 'cE', 300), 
                (n, 3, 'cF', 300), 
                (n, 4, 'cG', 300), 
                (n, 5, 'cH', 300), 
                (n, 6, 'cI', 300), 
                (n, 7, 'cJ', 300), 
                (n, 9, 'cK', 300), 
                (n, 10, 'cLA', 300), 
                (n, 11, 'cDA', 300), 
                (n, 12, 'cEA', 300), 
                (n, 13, 'cFA', 300), 
                (n, 14, 'cGA', 300), 
                (n, 15, 'cHA', 300), 
                (n, 16, 'cIA', 300), 
                (n, 17, 'cJA', 300), 
                (n, 19, 'cKA', 300), 
                (n, 20, 'cLA', 300), 
                ]

        ### h2b.browseTheCloud()
        for (rowCount, colCount, key2, timeoutSecs) in tryList:
            # just randomly pick the row and col cases.
            # colSepCase = random.randint(0,1)
            colSepCase = 1
            # using the comma is nice to ensure no craziness
            if (colSepCase==0):
                colSepHexString = '01'
                quoteChars = ",\'\"" # more choices for the unquoted string
            else:
                colSepHexString = '2c' # comma
                quoteChars = ""

            colSepChar = colSepHexString.decode('hex')
            colSepInt = int(colSepHexString, base=16)
            print "colSepChar:", colSepChar
            print "colSepInt", colSepInt

            rowSepCase = random.randint(0,1)
            # using this instead, makes the file, 'row-readable' in an editor
            if (rowSepCase==0):
                rowSepHexString = '0a' # newline
            else:
                rowSepHexString = '0d0a' # cr + newline (windows) \r\n

            rowSepChar = rowSepHexString.decode('hex')
            print "rowSepChar:", rowSepChar

            SEEDPERFILE = random.randint(0, sys.maxint)
            csvFilename = 'syn_enums_' + str(rowCount) + 'x' + str(colCount) + '.csv'
            csvPathname = SYNDATASETS_DIR + '/' + csvFilename

            print "Creating random", csvPathname
            write_syn_dataset(csvPathname, rowCount, colCount, SEEDPERFILE, 
                colSepChar=colSepChar, rowSepChar=rowSepChar, quoteChars=quoteChars)

            # FIX! does 'separator=' take ints or ?? hex format
            # looks like it takes the hex string (two chars)
            parseKey = h2o_cmd.parseFile(None, csvPathname, key2=key2, 
                timeoutSecs=30, separator=colSepInt)
            print csvFilename, 'parse time:', parseKey['response']['time']
            print "Parse result['destination_key']:", parseKey['destination_key']

            # We should be able to see the parse result?
            ### inspect = h2o_cmd.runInspect(None, parseKey['destination_key'])
            print "\n" + csvFilename
            missingValuesDict = h2o_cmd.check_enums_from_inspect(parseKey)
            if missingValuesDict:
                m = [str(k) + ":" + str(v) for k,v in missingValuesDict.iteritems()]
                raise Exception("Looks like columns got flipped to NAs: " + ", ".join(m))

            y = colCount
            kwargs = {'y': y, 'max_iter': 1, 'n_folds': 1, 'alpha': 0.2, 'lambda': 1e-5, 
                'case_mode': '=', 'case': 0}
            start = time.time()
            ### glm = h2o_cmd.runGLMOnly(parseKey=parseKey, timeoutSecs=timeoutSecs, pollTimeoutSecs=180, **kwargs)
            print "glm end on ", csvPathname, 'took', time.time() - start, 'seconds'
            ### h2o_glm.simpleCheckGLM(self, glm, None, **kwargs)

            # if not h2o.browse_disable:
            #     h2b.browseJsonHistoryAsUrlLastMatch("Inspect")
            #     time.sleep(5)

if __name__ == '__main__':
    h2o.unit_main()