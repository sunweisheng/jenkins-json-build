var gulp = require('gulp'),
    minifycss = require('gulp-minify-css'),
    rename = require('gulp-rename')

var versionJs = "v3.0.0";  //js版本
var versionCss = "v3.0.0";  //css版本

//压缩css
gulp.task('minifycss', function () {
    return gulp.src('src/*.css')    //需要操作的文件
        .pipe(rename({ suffix: '.min' }))   //rename压缩后的文件名
        .pipe(minifycss({
            compatibility: 'ie7'//保留ie7及以下兼容写法 类型：String 默认：''or'*' [启用兼容模式； 'ie7'：IE7兼容模式，'ie8'：IE8兼容模式，'*'：IE9+兼容模式]
        }))   //执行压缩
        .pipe(gulp.dest('css'));   //输出文件夹
});


//gulp 4.0版本新语法
gulp.task('default', gulp.parallel('minifycss'));