# Demo to test R functionality
# To invoke, need R 2.13.0 or higher
# R -f test_R_RF_diff_class.R --args Path/To/H2O_Load.R H2OServer:Port
args <- commandArgs(trailingOnly = TRUE)
if(length(args) != 2)
	  stop("Usage: R -f test_R_RF_diff_class.R --args Path/To/H2O_Load.R H2OServer:Port")

source(args[1], chdir = TRUE)
argsplit = strsplit(args[2], ":")[[1]]
localH2O = new("H2OClient", ip=argsplit[1], port=as.numeric(argsplit[2]))

# library(h2o)
# localH2O = new("H2OClient", ip="localhost", port=54321)

# Test of random forest using iris data set, different classes
iris.hex = h2o.importURL(localH2O, "https://raw.github.com/0xdata/h2o/master/smalldata/iris/iris22.csv", "iris.hex")
h2o.randomForest(y = "4", data = iris.hex, ntree = 50, depth = 100)
for(maxx in 0:3) {
  myIgnore = as.character(seq(0, maxx))
  iris.rf = h2o.randomForest(y = "4", x_ignore = myIgnore, data = iris.hex, ntree = 50, depth = 100)
  print(iris.rf)
}