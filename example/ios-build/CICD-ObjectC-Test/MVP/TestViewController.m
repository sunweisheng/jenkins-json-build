//
//  ViewController.m
//  CICD-ObjectC-Test
//
//  Created by Test on 2020/8/17.
//  Copyright © 2020 Test. All rights reserved.
//

#import "TestViewController.h"
#import "TestPresenter.h"
#import "TestProtocol.h"

@interface TestViewController () <TestProtocol>

@property (nonatomic, strong) TestPresenter *presenter;
@property (weak, nonatomic) IBOutlet UIButton *testButton;

@end

@implementation TestViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    // Do any additional setup after loading the view.
    self.presenter = [[TestPresenter alloc] initWithDelegate:self];
    
    self.testButton.backgroundColor = [UIColor orangeColor];
    self.testButton.layer.cornerRadius = 10.f;
    self.testButton.layer.masksToBounds = YES;
    
}

#pragma mark - Target

- (IBAction)targetForTestButton:(id)sender {
    [self.presenter personClickTestButton];
}

#pragma mark - TestProtocol

- (void)protocolShowAlertMsg:(NSString *)message {
    
    UIAlertController *alertController = [UIAlertController alertControllerWithTitle:@"提示:" message:message preferredStyle:UIAlertControllerStyleAlert];
    UIAlertAction *cancelAction = [UIAlertAction actionWithTitle:@"关闭" style:UIAlertActionStyleCancel handler:^(UIAlertAction * _Nonnull action) {
        
    }];
    UIAlertAction *confirmAction = [UIAlertAction actionWithTitle:@"确定" style:UIAlertActionStyleDefault handler:^(UIAlertAction * _Nonnull action) {
        
    }];
    [alertController addAction:cancelAction];
    [alertController addAction:confirmAction];
    [self presentViewController:alertController animated:YES completion:nil];
}

- (void)protocolClickCloseButton {
    NSLog(@"关闭按钮被点击");
}

- (void)protocolClickConfirmButton {
    NSLog(@"确定按钮被点击");
}

@end
